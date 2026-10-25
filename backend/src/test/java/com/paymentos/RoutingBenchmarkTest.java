package com.paymentos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentos.dto.CreatePaymentRequest;
import com.paymentos.dto.PaymentResponse;
import com.paymentos.entity.CircuitBreakerState;
import com.paymentos.entity.Payment;
import com.paymentos.entity.PaymentAttempt;
import com.paymentos.entity.PaymentAttemptStatus;
import com.paymentos.entity.PaymentEvent;
import com.paymentos.entity.PaymentStatus;
import com.paymentos.entity.Processor;
import com.paymentos.repository.PaymentAttemptRepository;
import com.paymentos.repository.PaymentEventRepository;
import com.paymentos.repository.PaymentRepository;
import com.paymentos.repository.ProcessorRepository;
import com.paymentos.service.PaymentService;
import com.paymentos.service.ProcessorRouter;
import com.paymentos.service.ProcessorRoutingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class RoutingBenchmarkTest {
    private static final int PAYMENTS_PER_SEED = 100;
    private static final List<Long> SEEDS = List.of(20261026L, 7341289L, 99182731L);
    private static final List<ProcessorRoutingStrategy> STRATEGIES = List.of(
        ProcessorRoutingStrategy.ROUND_ROBIN,
        ProcessorRoutingStrategy.HEALTH_AWARE
    );

    @Autowired private ObjectMapper objectMapper;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private ProcessorRepository processorRepository;
    @Autowired private ProcessorRouter processorRouter;

    @Test
    @EnabledIfSystemProperty(named = "paymentos.benchmark.run", matches = "true")
    void createsPairedRoutingBenchmarkResults() throws IOException {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(runScenario("normal", false));
        scenarios.add(runScenario("processor-a-failure", true));
        Map<String, Object> recovery = runRecoveryCheck();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", 1);
        output.put("generatedAt", Instant.now().toString());
        output.put("methodology", Map.of(
            "execution", "In-process PaymentService benchmark using Spring Boot and H2 in PostgreSQL compatibility mode",
            "paymentsPerStrategyPerScenario", PAYMENTS_PER_SEED * SEEDS.size(),
            "seeds", SEEDS,
            "workload", "Each seed generates the same merchant, amount, currency, and customer references for both strategies; each run uses unique deterministic idempotency keys.",
            "stateIsolation", "Payment, attempt, event, and processor records are deleted and recreated and the round-robin cursor is reset before every seed and strategy run.",
            "executionOrder", "Strategy execution order alternates by seed to reduce systematic warm-up bias.",
            "latency", "Processor attempt durationMs recorded by the simulator; throughput uses wall-clock time around payment creation calls only.",
            "normalCondition", "All processors enabled with zero configured pre-execution failure and timeout probabilities; simulator declines remain possible.",
            "failureCondition", "Processor A configured for 100% pre-execution failure probability; processors B and C remain at zero configured failure and timeout probabilities.",
            "limitations", "Synthetic in-process measurements are not external-network latency or production throughput. Results are observations, not a guaranteed strategy ranking."
        ));
        output.put("recoveryCheck", recovery);
        output.put("scenarios", scenarios);

        Path outputPath = Path.of("benchmark", "results", "routing-benchmark.json");
        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), output);
        writeReport(output, Path.of("benchmark", "results", "routing-benchmark-report.md"));

        assertEquals(2, scenarios.size());
        Map<?, ?> normalResults = (Map<?, ?>) scenarios.getFirst().get("results");
        Map<?, ?> roundRobin = (Map<?, ?>) normalResults.get("ROUND_ROBIN");
        assertEquals(PAYMENTS_PER_SEED * SEEDS.size(), ((Number) roundRobin.get("totalPayments")).intValue());
        assertEquals(Boolean.TRUE, recovery.get("expiredOpenCircuitSelectedForProbe"));
        assertEquals(Boolean.TRUE, recovery.get("oneProbeReservation"));
        assertEquals(Boolean.TRUE, recovery.get("successfulProbeClosedCircuit"));
    }

    private Map<String, Object> runScenario(String name, boolean injectProcessorFailure) {
        Map<String, Object> comparisons = new LinkedHashMap<>();
        Map<ProcessorRoutingStrategy, Aggregate> aggregates = new LinkedHashMap<>();
        for (ProcessorRoutingStrategy strategy : STRATEGIES) {
            aggregates.put(strategy, new Aggregate());
        }
        for (int seedIndex = 0; seedIndex < SEEDS.size(); seedIndex++) {
            long seed = SEEDS.get(seedIndex);
            List<WorkItem> workload = createWorkload(seed);
            List<ProcessorRoutingStrategy> executionOrder = seedIndex % 2 == 0
                ? STRATEGIES
                : List.of(ProcessorRoutingStrategy.HEALTH_AWARE, ProcessorRoutingStrategy.ROUND_ROBIN);
            for (ProcessorRoutingStrategy strategy : executionOrder) {
                Aggregate aggregate = aggregates.get(strategy);
                resetState(injectProcessorFailure);
                long startedAt = System.nanoTime();
                for (WorkItem workItem : workload) {
                    PaymentResponse response = paymentService.createPayment(workItem.request(), workItem.idempotencyKey(), strategy);
                    aggregate.recordPayment(response.getStatus());
                }
                aggregate.elapsedNanos += System.nanoTime() - startedAt;

                List<Payment> payments = paymentRepository.findAll();
                assertEquals(PAYMENTS_PER_SEED, payments.size());
                paymentAttemptRepository.findAllWithProcessor().forEach(aggregate::recordAttempt);
                processorRepository.findAll().forEach(aggregate::recordProcessorState);
            }
        }
        for (ProcessorRoutingStrategy strategy : STRATEGIES) {
            comparisons.put(strategy.name(), aggregates.get(strategy).toMap(PAYMENTS_PER_SEED * SEEDS.size()));
        }

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("name", name);
        scenario.put("processorAConfiguredFailureProbability", injectProcessorFailure ? 1.0 : 0.0);
        scenario.put("processorAConfiguredTimeoutProbability", 0.0);
        scenario.put("processorBAndCConfiguredFailureProbability", 0.0);
        scenario.put("processorBAndCConfiguredTimeoutProbability", 0.0);
        scenario.put("results", comparisons);
        return scenario;
    }

    private void resetState(boolean injectProcessorFailure) {
        processorRouter.resetRoundRobinCursor();
        paymentAttemptRepository.deleteAllInBatch();
        paymentEventRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        processorRepository.deleteAllInBatch();

        Processor a = benchmarkProcessor("Benchmark A", 120, 0.96, 140, injectProcessorFailure ? 1.0 : 0.0);
        Processor b = benchmarkProcessor("Benchmark B", 170, 0.92, 180, 0.0);
        Processor c = benchmarkProcessor("Benchmark C", 220, 0.88, 240, 0.0);
        processorRepository.saveAllAndFlush(List.of(a, b, c));
    }

    private Processor benchmarkProcessor(String name, int baseLatencyMs, double successRate, long latencyMs, double failureProbability) {
        Processor processor = new Processor(name, baseLatencyMs, failureProbability, 0.0);
        processor.setEnabled(true);
        processor.setRecentSuccessRate(successRate);
        processor.setRecentLatencyMs(latencyMs);
        processor.setCircuitBreakerState(CircuitBreakerState.CLOSED);
        return processor;
    }

    private List<WorkItem> createWorkload(long seed) {
        Random random = new Random(seed);
        List<WorkItem> workload = new ArrayList<>(PAYMENTS_PER_SEED);
        for (int index = 0; index < PAYMENTS_PER_SEED; index++) {
            CreatePaymentRequest request = new CreatePaymentRequest();
            request.setMerchantId("benchmark-merchant-" + seed);
            request.setAmountMinor(100L + random.nextInt(999_900));
            request.setCurrency("USD");
            request.setCustomerReference("seed-" + seed + "-customer-" + random.nextLong());
            workload.add(new WorkItem(request, "bench-" + seed + "-" + index));
        }
        return workload;
    }

    private Map<String, Object> runRecoveryCheck() {
        resetState(false);
        List<Processor> processors = processorRepository.findAll();
        Processor recovering = processors.stream()
            .filter(processor -> processor.getName().equals("Benchmark A"))
            .findFirst()
            .orElseThrow();
        recovering.setCircuitBreakerState(CircuitBreakerState.OPEN);
        recovering.setCircuitOpenUntil(Instant.now().minusSeconds(1));
        processors.stream()
            .filter(processor -> !processor.getName().equals(recovering.getName()))
            .forEach(processor -> processor.setEnabled(false));
        processorRepository.saveAllAndFlush(processors);
        processorRepository.flush();

        Processor probe = processorRouter.selectProcessor(ProcessorRoutingStrategy.HEALTH_AWARE);
        boolean enteredHalfOpen = probe.getCircuitBreakerState() == CircuitBreakerState.HALF_OPEN
            && probe.isHalfOpenProbeInFlight();
        boolean secondProbeRejected = processorRouter.selectProcessor(
            ProcessorRoutingStrategy.HEALTH_AWARE, java.util.Set.of()
        ).isEmpty();
        processorRouter.recordOutcome(probe, PaymentAttemptStatus.SUCCESS, 100);

        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("expiredOpenCircuitSelectedForProbe", enteredHalfOpen);
        recovery.put("oneProbeReservation", secondProbeRejected);
        recovery.put("successfulProbeClosedCircuit",
            processorRepository.findById(probe.getId()).orElseThrow().getCircuitBreakerState() == CircuitBreakerState.CLOSED);
        recovery.put("cooldownSeconds", 30);
        return recovery;
    }

    private void writeReport(Map<String, Object> output, Path reportPath) throws IOException {
        StringBuilder report = new StringBuilder("# PaymentOS controlled routing benchmark\n\n");
        report.append("Generated: ").append(output.get("generatedAt")).append("\n\n");
        report.append("This is an in-process H2 simulator benchmark, not a production-network performance test. ")
            .append("Paired strategies use identical seeded workloads and reset processor/database state for every seed.\n\n");
        report.append("| Scenario | Strategy | Payments (S/F/U) | Payment success | Attempts | Attempt success | Avg attempt ms | P95 attempt ms | Payments/s | Attempt processor distribution | End circuit states |\n");
        report.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) output.get("scenarios");
        for (Map<String, Object> scenario : scenarios) {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> results = (Map<String, Map<String, Object>>) scenario.get("results");
            for (String strategy : List.of("ROUND_ROBIN", "HEALTH_AWARE")) {
                Map<String, Object> result = results.get(strategy);
                report.append("| ").append(scenario.get("name")).append(" | ").append(strategy)
                    .append(" | ").append(result.get("totalPayments")).append(" (")
                    .append(result.get("successfulPayments")).append("/")
                    .append(result.get("failedPayments")).append("/")
                    .append(result.get("unknownPayments")).append(")")
                    .append(" | ").append(result.get("paymentSuccessRatePct")).append("%")
                    .append(" | ").append(result.get("processorAttempts"))
                    .append(" | ").append(result.get("attemptSuccessRatePct")).append("%")
                    .append(" | ").append(result.get("averageAttemptLatencyMs"))
                    .append(" | ").append(result.get("p95AttemptLatencyMs"))
                    .append(" | ").append(result.get("throughputPaymentsPerSecond"))
                    .append(" | ").append(result.get("processorSelectionDistribution"))
                    .append(" | ").append(result.get("processorCircuitStateAtEndOfEachSeedRun")).append(" |\n");
            }
        }
        report.append("\n## Interpretation\n\n")
            .append("Payment-level status rates use one terminal payment outcome per payment. Attempt-level success rates use individual processor attempts, including retries. ")
            .append("Latency percentiles use simulator-recorded attempt durations; throughput measures local service-call wall time. ")
            .append("The separate recovery check verified expired OPEN → HALF_OPEN, one reserved probe, and a successful probe closing the circuit. ")
            .append("Treat these measurements as reproducible simulator observations, not evidence of real network or production performance.\n");
        Files.writeString(reportPath, report);
    }

    private record WorkItem(CreatePaymentRequest request, String idempotencyKey) { }

    private static final class Aggregate {
        private final Map<PaymentStatus, Long> paymentCounts = new EnumMap<>(PaymentStatus.class);
        private final Map<PaymentAttemptStatus, Long> attemptCounts = new EnumMap<>(PaymentAttemptStatus.class);
        private final Map<String, Long> processorSelections = new LinkedHashMap<>();
        private final Map<CircuitBreakerState, Long> processorEndStates = new EnumMap<>(CircuitBreakerState.class);
        private final List<Long> latencies = new ArrayList<>();
        private long attempts;
        private long successfulAttempts;
        private long elapsedNanos;

        private void recordPayment(PaymentStatus status) {
            paymentCounts.merge(status, 1L, Long::sum);
        }

        private void recordAttempt(PaymentAttempt attempt) {
            attempts++;
            attemptCounts.merge(attempt.getStatus(), 1L, Long::sum);
            if (attempt.getStatus() == PaymentAttemptStatus.SUCCESS) {
                successfulAttempts++;
            }
            if (attempt.getProcessor() != null) {
                processorSelections.merge(attempt.getProcessor().getName(), 1L, Long::sum);
            }
            if (attempt.getDurationMs() != null) {
                latencies.add(attempt.getDurationMs());
            }
        }

        private void recordProcessorState(Processor processor) {
            processorEndStates.merge(processor.getCircuitBreakerState(), 1L, Long::sum);
        }

        private Map<String, Object> toMap(int expectedPayments) {
            long totalPayments = paymentCounts.values().stream().mapToLong(Long::longValue).sum();
            assertEquals(expectedPayments, totalPayments);
            latencies.sort(Long::compareTo);
            long succeeded = paymentCounts.getOrDefault(PaymentStatus.SUCCEEDED, 0L);
            long failed = paymentCounts.getOrDefault(PaymentStatus.FAILED, 0L);
            long unknown = paymentCounts.getOrDefault(PaymentStatus.UNKNOWN, 0L);
            double averageLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
            long p95Latency = latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double paymentSuccessRate = totalPayments == 0 ? 0 : succeeded * 100.0 / totalPayments;
            double attemptSuccessRate = attempts == 0 ? 0 : successfulAttempts * 100.0 / attempts;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalPayments", totalPayments);
            result.put("successfulPayments", succeeded);
            result.put("failedPayments", failed);
            result.put("unknownPayments", unknown);
            result.put("paymentSuccessRatePct", round(paymentSuccessRate));
            result.put("processorAttempts", attempts);
            result.put("attemptOutcomes", attemptCounts);
            result.put("successfulProcessorAttempts", successfulAttempts);
            result.put("attemptSuccessRatePct", round(attemptSuccessRate));
            result.put("averageAttemptLatencyMs", round(averageLatency));
            result.put("p95AttemptLatencyMs", p95Latency);
            result.put("throughputPaymentsPerSecond", round(elapsedSeconds > 0 ? expectedPayments / elapsedSeconds : 0));
            result.put("processorSelectionDistribution", processorSelections);
            result.put("processorCircuitStateAtEndOfEachSeedRun", processorEndStates);
            result.put("measuredPaymentCallTimeMs", round(elapsedNanos / 1_000_000.0));
            return result;
        }

        private static double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }
}
