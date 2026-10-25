package com.paymentos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentos.dto.CreatePaymentRequest;
import com.paymentos.entity.Payment;
import com.paymentos.entity.PaymentAttempt;
import com.paymentos.entity.PaymentAttemptStatus;
import com.paymentos.entity.Processor;
import com.paymentos.entity.PaymentStatus;
import com.paymentos.entity.CircuitBreakerState;
import com.paymentos.repository.PaymentAttemptRepository;
import com.paymentos.repository.PaymentRepository;
import com.paymentos.repository.ProcessorRepository;
import com.paymentos.service.PaymentService;
import com.paymentos.service.ProcessorSimulationService;
import com.paymentos.service.ProcessorRoutingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private ProcessorRepository processorRepository;

    @Autowired
    private ProcessorSimulationService processorSimulationService;

    @Autowired
    private PaymentService paymentService;

    @Test
    void shouldCreatePaymentWithIdempotencyKey() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setMerchantId("merchant_001");
        request.setAmountMinor(250000L);
        request.setCurrency("INR");
        request.setCustomerReference("customer_123");

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-001")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentId").exists())
            .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void shouldReturnSamePaymentForDuplicateIdempotentRequest() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setMerchantId("merchant_duplicate");
        request.setAmountMinor(5000L);
        request.setCurrency("INR");
        request.setCustomerReference("customer_abc");

        String payload = objectMapper.writeValueAsString(request);

        MvcResult first = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-duplicate")
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentId").exists())
            .andReturn();

        Payment stored = paymentRepository.findByMerchantIdAndIdempotencyKey("merchant_duplicate", "idem-duplicate").orElseThrow();
        int attemptCount = paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(stored).size();

        MvcResult duplicate = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-duplicate")
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentId").exists())
            .andReturn();

        assertEquals(
            objectMapper.readTree(first.getResponse().getContentAsString()).get("paymentId"),
            objectMapper.readTree(duplicate.getResponse().getContentAsString()).get("paymentId")
        );

        assertEquals(attemptCount, paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(stored).size());
    }

    @Test
    void shouldRejectConflictingPayloadForSameIdempotencyKey() throws Exception {
        CreatePaymentRequest first = new CreatePaymentRequest();
        first.setMerchantId("merchant_conflict");
        first.setAmountMinor(250000L);
        first.setCurrency("INR");
        first.setCustomerReference("customer_123");

        CreatePaymentRequest second = new CreatePaymentRequest();
        second.setMerchantId("merchant_conflict");
        second.setAmountMinor(999999L);
        second.setCurrency("INR");
        second.setCustomerReference("customer_456");

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-conflict")
                .content(objectMapper.writeValueAsString(first)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-conflict")
                .content(objectMapper.writeValueAsString(second)))
            .andExpect(status().isConflict());
    }

    @Test
    void shouldReconcileUnknownPayment() throws Exception {
        Processor processor = processorRepository.findByName("Processor A").orElseThrow();
        Payment payment = new Payment();
        payment.setMerchantId("merchant_reconcile");
        payment.setIdempotencyKey("idem-reconcile");
        payment.setRequestFingerprint("reconcile-fingerprint");
        payment.setAmountMinor(777L);
        payment.setCurrency("USD");
        payment.setCustomerReference("customer_reconcile");
        payment.setStatus(PaymentStatus.UNKNOWN);
        payment.setSelectedProcessorId(processor.getName());
        payment = paymentRepository.saveAndFlush(payment);

        String processorRequestId = "original-request-" + payment.getId();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setPayment(payment);
        attempt.setProcessor(processor);
        attempt.setProcessorRequestId(processorRequestId);
        attempt.setStatus(PaymentAttemptStatus.TIMEOUT_UNKNOWN);
        attempt.setStartedAt(java.time.Instant.now());
        attempt.setCompletedAt(java.time.Instant.now());
        paymentAttemptRepository.saveAndFlush(attempt);
        PaymentStatus expected = processorSimulationService.reconcileUnknown(processor, processorRequestId)
            == PaymentAttemptStatus.SUCCESS ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;

        mockMvc.perform(post("/api/payments/{id}/reconcile", payment.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUCCEEDED\",\"reason\":\"manual reconciliation\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(expected.name()));

        assertEquals(expected == PaymentStatus.SUCCEEDED ? PaymentAttemptStatus.SUCCESS : PaymentAttemptStatus.DECLINED,
            paymentAttemptRepository.findById(attempt.getId()).orElseThrow().getStatus());
    }

    @Test
    void shouldNotReconcileAnUnknownPaymentWithoutTimedOutAttempt() throws Exception {
        Payment payment = new Payment();
        payment.setMerchantId("merchant-no-attempt");
        payment.setIdempotencyKey("idem-no-attempt");
        payment.setRequestFingerprint("fingerprint-no-attempt");
        payment.setAmountMinor(777L);
        payment.setCurrency("USD");
        payment.setCustomerReference("customer-no-attempt");
        payment.setStatus(PaymentStatus.UNKNOWN);
        payment = paymentRepository.saveAndFlush(payment);

        mockMvc.perform(post("/api/payments/{id}/reconcile", payment.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"reconcile\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void simulationAndRoutingAnalyticsUsePersistedAttempts() throws Exception {
        Set<Long> priorPaymentIds = paymentRepository.findAll().stream().map(Payment::getId)
            .collect(java.util.stream.Collectors.toSet());
        mockMvc.perform(post("/api/simulations/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":2,\"routingStrategy\":\"ROUND_ROBIN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestedCount").value(2))
            .andExpect(jsonPath("$.completedCount").value(2))
            .andExpect(jsonPath("$.routingStrategy").value("ROUND_ROBIN"));

        mockMvc.perform(post("/api/simulations/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":2,\"routingStrategy\":\"HEALTH_AWARE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestedCount").value(2))
            .andExpect(jsonPath("$.completedCount").value(2))
            .andExpect(jsonPath("$.routingStrategy").value("HEALTH_AWARE"));

        var generated = paymentRepository.findAll().stream()
            .filter(payment -> !priorPaymentIds.contains(payment.getId()))
            .toList();
        assertEquals(4, generated.size());
        assertEquals(4, generated.stream().map(Payment::getIdempotencyKey).distinct().count(),
            "separate simulation batches must not reuse idempotency keys");
        assertEquals(2, generated.stream().filter(payment -> "ROUND_ROBIN".equals(payment.getRoutingStrategy())).count());
        assertEquals(2, generated.stream().filter(payment -> "HEALTH_AWARE".equals(payment.getRoutingStrategy())).count());
        generated.forEach(payment -> assertTrue(!paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(payment).isEmpty()));

        mockMvc.perform(get("/api/analytics/routing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.strategy == 'ROUND_ROBIN')]").exists())
            .andExpect(jsonPath("$[?(@.strategy == 'HEALTH_AWARE')]").exists());
    }

    @Test
    void shouldPersistFailedPaymentWithoutAttemptsWhenEveryProcessorIsUnavailable() throws Exception {
        var processors = processorRepository.findAll();
        processors.forEach(processor -> {
            processor.setEnabled(true);
            processor.setCircuitBreakerState(CircuitBreakerState.OPEN);
            processor.setCircuitOpenUntil(java.time.Instant.now().plusSeconds(60));
            processorRepository.save(processor);
        });

        try {
            CreatePaymentRequest request = new CreatePaymentRequest();
            request.setMerchantId("merchant-no-route");
            request.setAmountMinor(1234L);
            request.setCurrency("USD");
            request.setCustomerReference("unavailable-test");
            String key = "no-route-" + java.util.UUID.randomUUID();

            var response = paymentService.createPayment(request, key);
            assertNotNull(response);
            assertEquals(PaymentStatus.FAILED, response.getStatus());
            Payment stored = paymentRepository.findByMerchantIdAndIdempotencyKey(request.getMerchantId(), key).orElseThrow();
            assertEquals(PaymentStatus.FAILED, stored.getStatus());
            assertEquals(0, paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(stored).size());

            mockMvc.perform(post("/api/simulations/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"count\":2,\"routingStrategy\":\"ROUND_ROBIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(2))
                .andExpect(jsonPath("$.paymentStatusCounts.FAILED").value(2));
        } finally {
            processors.forEach(processor -> {
                processor.setEnabled(true);
                processor.setCircuitBreakerState(CircuitBreakerState.CLOSED);
                processor.setCircuitOpenUntil(null);
                processor.setHalfOpenProbeInFlight(false);
                processorRepository.save(processor);
            });
        }
    }

    @Test
    void simulationFailureInjectionAffectsAttemptsAndOpensCircuitBreaker() throws Exception {
        var processors = processorRepository.findAll();
        var original = processors.stream().collect(java.util.stream.Collectors.toMap(
            Processor::getId,
            processor -> new ProcessorConfig(
                processor.isEnabled(),
                processor.getFailureProbability(),
                processor.getTimeoutProbability(),
                processor.getCircuitBreakerState(),
                processor.getCircuitOpenUntil(),
                processor.isHalfOpenProbeInFlight(),
                processor.getRecentSuccessRate(),
                processor.getConsecutiveFailures()
            )
        ));
        Processor target = processors.stream()
            .filter(processor -> "Processor A".equals(processor.getName()))
            .findFirst()
            .orElseThrow();
        Set<Long> priorPaymentIds = paymentRepository.findAll().stream().map(Payment::getId)
            .collect(java.util.stream.Collectors.toSet());

        try {
            processors.forEach(processor -> {
                processor.setEnabled(processor.getId().equals(target.getId()));
                processor.setCircuitBreakerState(CircuitBreakerState.CLOSED);
                processor.setCircuitOpenUntil(null);
                processor.setHalfOpenProbeInFlight(false);
                processor.setConsecutiveFailures(0);
            });
            target.setRecentSuccessRate(1.0);
            processorRepository.saveAllAndFlush(processors);

            mockMvc.perform(post("/api/simulations/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"count\":3,\"routingStrategy\":\"ROUND_ROBIN\",\"processorId\":"
                        + target.getId() + ",\"failureProbability\":1.0,\"timeoutProbability\":0.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(3))
                .andExpect(jsonPath("$.paymentStatusCounts.FAILED").value(3));

            var generated = paymentRepository.findAll().stream()
                .filter(payment -> !priorPaymentIds.contains(payment.getId()))
                .toList();
            assertEquals(3, generated.size());
            assertEquals(2, generated.stream()
                .mapToLong(payment -> paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(payment).size())
                .sum(), "the third payment should persist without dispatch after the circuit opens");
            assertEquals(CircuitBreakerState.OPEN,
                processorRepository.findById(target.getId()).orElseThrow().getCircuitBreakerState());
            assertEquals(1.0, processorRepository.findById(target.getId()).orElseThrow().getFailureProbability());
        } finally {
            processors.forEach(processor -> {
                ProcessorConfig config = original.get(processor.getId());
                processor.setEnabled(config.enabled());
                processor.setFailureProbability(config.failureProbability());
                processor.setTimeoutProbability(config.timeoutProbability());
                processor.setCircuitBreakerState(config.circuitBreakerState());
                processor.setCircuitOpenUntil(config.circuitOpenUntil());
                processor.setHalfOpenProbeInFlight(config.probeInFlight());
                processor.setRecentSuccessRate(config.recentSuccessRate());
                processor.setConsecutiveFailures(config.consecutiveFailures());
            });
            processorRepository.saveAll(processors);
        }
    }

    @Test
    void timeoutOutcomeIsNotRetriedAndRemainsUnknownUntilReconciliation() throws Exception {
        var processors = processorRepository.findAll();
        var original = processors.stream().collect(java.util.stream.Collectors.toMap(
            Processor::getId,
            processor -> new ProcessorConfig(
                processor.isEnabled(),
                processor.getFailureProbability(),
                processor.getTimeoutProbability(),
                processor.getCircuitBreakerState(),
                processor.getCircuitOpenUntil(),
                processor.isHalfOpenProbeInFlight(),
                processor.getRecentSuccessRate(),
                processor.getConsecutiveFailures()
            )
        ));
        Processor firstChoice = processors.stream()
            .filter(processor -> "Processor A".equals(processor.getName()))
            .findFirst()
            .orElseThrow();
        String customerReference = timeoutCustomerReference("merchant-timeout", "USD", 9100L);

        try {
            processors.forEach(processor -> processor.setEnabled(processor.getId().equals(firstChoice.getId())));
            firstChoice.setFailureProbability(0.0);
            firstChoice.setTimeoutProbability(0.25);
            processorRepository.saveAll(processors);

            CreatePaymentRequest request = new CreatePaymentRequest();
            request.setMerchantId("merchant-timeout");
            request.setAmountMinor(9100L);
            request.setCurrency("USD");
            request.setCustomerReference(customerReference);
            String key = "timeout-no-retry-" + java.util.UUID.randomUUID();

            var response = paymentService.createPayment(request, key, ProcessorRoutingStrategy.HEALTH_AWARE);
            assertEquals(PaymentStatus.UNKNOWN, response.getStatus());
            Payment stored = paymentRepository.findByMerchantIdAndIdempotencyKey(request.getMerchantId(), key).orElseThrow();
            var attempts = paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(stored);
            assertEquals(1, attempts.size(), "uncertain processor outcomes must not be retried");
            assertEquals(PaymentAttemptStatus.TIMEOUT_UNKNOWN, attempts.get(0).getStatus());

            paymentService.reconcilePayment(stored.getId(), "focused timeout test");
            assertEquals(1, paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(stored).size());
            assertTrue(EnumSet.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED)
                .contains(paymentRepository.findById(stored.getId()).orElseThrow().getStatus()));
        } finally {
            processors.forEach(processor -> {
                ProcessorConfig config = original.get(processor.getId());
                processor.setEnabled(config.enabled());
                processor.setFailureProbability(config.failureProbability());
                processor.setTimeoutProbability(config.timeoutProbability());
                processor.setCircuitBreakerState(config.circuitBreakerState());
                processor.setCircuitOpenUntil(config.circuitOpenUntil());
                processor.setHalfOpenProbeInFlight(config.probeInFlight());
                processor.setRecentSuccessRate(config.recentSuccessRate());
                processor.setConsecutiveFailures(config.consecutiveFailures());
            });
            processorRepository.saveAll(processors);
        }
    }

    private String timeoutCustomerReference(String merchantId, String currency, long amountMinor) throws Exception {
        for (int index = 0; ; index++) {
            String customerReference = "timeout-customer-" + index;
            String source = merchantId + "|" + currency + "|" + amountMinor + "|" + customerReference;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String fingerprint = java.util.HexFormat.of().formatHex(digest);
            if (Math.floorMod(fingerprint.hashCode(), 100) < 25) {
                return customerReference;
            }
        }
    }

    private record ProcessorConfig(
        boolean enabled,
        double failureProbability,
        double timeoutProbability,
        CircuitBreakerState circuitBreakerState,
        java.time.Instant circuitOpenUntil,
        boolean probeInFlight,
        double recentSuccessRate,
        int consecutiveFailures
    ) { }
}
