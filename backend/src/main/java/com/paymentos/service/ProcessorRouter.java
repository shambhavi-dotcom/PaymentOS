package com.paymentos.service;

import com.paymentos.entity.CircuitBreakerState;
import com.paymentos.entity.PaymentAttemptStatus;
import com.paymentos.entity.Processor;
import com.paymentos.repository.ProcessorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ProcessorRouter {
    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_DURATION_SECONDS = 30;

    private final ProcessorRepository processorRepository;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public ProcessorRouter(ProcessorRepository processorRepository) {
        this.processorRepository = processorRepository;
    }

    public void resetRoundRobinCursor() {
        roundRobinIndex.set(0);
    }

    @Transactional
    public Processor selectProcessor() {
        return selectProcessor(ProcessorRoutingStrategy.HEALTH_AWARE);
    }

    @Transactional
    public Processor selectProcessor(ProcessorRoutingStrategy strategy) {
        return selectProcessor(strategy, Set.of())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No processors are available for routing"));
    }

    @Transactional
    public java.util.Optional<Processor> selectProcessor(ProcessorRoutingStrategy strategy, Set<String> excludedNames) {
        List<Processor> enabled = processorRepository.findEnabledForRouting();
        if (enabled.isEmpty()) {
            return java.util.Optional.empty();
        }

        Instant now = Instant.now();
        for (Processor processor : enabled) {
            if (processor.getCircuitBreakerState() == CircuitBreakerState.OPEN
                && processor.getCircuitOpenUntil() != null
                && !processor.getCircuitOpenUntil().isAfter(now)) {
                processor.setCircuitBreakerState(CircuitBreakerState.HALF_OPEN);
                processor.setHalfOpenProbeInFlight(false);
            }
        }

        List<Processor> candidates = enabled.stream()
            .filter(processor -> !excludedNames.contains(processor.getName()))
            .filter(processor -> processor.getCircuitBreakerState() == CircuitBreakerState.CLOSED
                || (processor.getCircuitBreakerState() == CircuitBreakerState.HALF_OPEN
                    && !processor.isHalfOpenProbeInFlight()))
            .toList();
        if (candidates.isEmpty()) {
            processorRepository.saveAll(enabled);
            return java.util.Optional.empty();
        }

        Comparator<Processor> comparator = Comparator
            .comparingInt((Processor processor) -> statePriority(processor.getCircuitBreakerState()));
        if (strategy == ProcessorRoutingStrategy.HEALTH_AWARE) {
            comparator = comparator.thenComparing(Comparator.comparingDouble(Processor::getRecentSuccessRate).reversed())
                .thenComparingLong(Processor::getRecentLatencyMs);
        } else if (strategy == ProcessorRoutingStrategy.LATENCY_AWARE) {
            comparator = comparator.thenComparingLong(Processor::getRecentLatencyMs)
                .thenComparing(Comparator.comparingDouble(Processor::getRecentSuccessRate).reversed());
        } else {
            comparator = comparator.thenComparingLong(Processor::getId);
        }
        List<Processor> ranked = candidates.stream().sorted(comparator).toList();
        Processor selected = strategy == ProcessorRoutingStrategy.ROUND_ROBIN
            ? ranked.get(Math.floorMod(roundRobinIndex.getAndIncrement(), ranked.size()))
            : ranked.get(0);

        if (selected.getCircuitBreakerState() == CircuitBreakerState.HALF_OPEN) {
            selected.setHalfOpenProbeInFlight(true);
        }
        return java.util.Optional.of(processorRepository.save(selected));
    }

    @Transactional
    public void recordOutcome(Processor processor, PaymentAttemptStatus outcome, long latencyMs) {
        if (processor == null) {
            return;
        }

        double previousRate = processor.getRecentSuccessRate();
        double adjustedRate = switch (outcome) {
            case SUCCESS -> Math.min(1.0, previousRate * 0.85 + 0.15);
            case DECLINED -> Math.min(1.0, previousRate * 0.9 + 0.05);
            case FAILED_BEFORE_EXECUTION, TIMEOUT_UNKNOWN -> Math.max(0.0, previousRate * 0.7 - 0.15);
        };

        processor.setRecentSuccessRate(adjustedRate);
        processor.setRecentLatencyMs(Math.max(50L, latencyMs));

        boolean processorResponded = outcome == PaymentAttemptStatus.SUCCESS || outcome == PaymentAttemptStatus.DECLINED;
        if (processorResponded) {
            processor.setConsecutiveFailures(0);
            processor.setLastSuccessAt(Instant.now());
            processor.setCircuitBreakerState(CircuitBreakerState.CLOSED);
            processor.setCircuitOpenUntil(null);
            processor.setHalfOpenProbeInFlight(false);
        } else {
            processor.setConsecutiveFailures(processor.getConsecutiveFailures() + 1);
            processor.setLastFailureAt(Instant.now());
            processor.setHalfOpenProbeInFlight(false);
            if (processor.getConsecutiveFailures() >= FAILURE_THRESHOLD
                || processor.getCircuitBreakerState() == CircuitBreakerState.HALF_OPEN
                || adjustedRate < 0.35) {
                processor.setCircuitBreakerState(CircuitBreakerState.OPEN);
                processor.setCircuitOpenUntil(Instant.now().plusSeconds(OPEN_DURATION_SECONDS));
            } else {
                processor.setCircuitBreakerState(CircuitBreakerState.CLOSED);
            }
        }

        processorRepository.save(processor);
    }

    private int statePriority(CircuitBreakerState state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }
}
