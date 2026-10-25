package com.paymentos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentos.dto.CreatePaymentRequest;
import com.paymentos.dto.PaymentResponse;
import com.paymentos.entity.CircuitBreakerState;
import com.paymentos.entity.Payment;
import com.paymentos.entity.Processor;
import com.paymentos.repository.PaymentAttemptRepository;
import com.paymentos.repository.PaymentRepository;
import com.paymentos.repository.ProcessorRepository;
import com.paymentos.service.PaymentService;
import com.paymentos.service.ProcessorRouter;
import com.paymentos.service.ProcessorRoutingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAYMENTOS_POSTGRES_TEST_URL", matches = ".+")
class PostgresPaymentIntegrationTest {
    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("PAYMENTOS_POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PAYMENTOS_POSTGRES_TEST_USERNAME", "paymentos"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PAYMENTOS_POSTGRES_TEST_PASSWORD", "paymentos"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ProcessorRepository processorRepository;

    @Autowired
    private ProcessorRouter processorRouter;

    @Test
    void postgresMigrationsAndIdempotentPaymentCreationWork() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setMerchantId("postgres-integration-merchant");
        request.setAmountMinor(8900L);
        request.setCurrency("USD");
        request.setCustomerReference("postgres-test-customer");
        String payload = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/payments")
                .contentType(APPLICATION_JSON)
                .header("Idempotency-Key", "postgres-integration-key")
                .content(payload))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/payments")
                .contentType(APPLICATION_JSON)
                .header("Idempotency-Key", "postgres-integration-key")
                .content(payload))
            .andExpect(status().isCreated());

        assertEquals(1, paymentRepository.countByMerchantIdAndIdempotencyKey(
            "postgres-integration-merchant",
            "postgres-integration-key"
        ));
    }

    @Test
    void concurrentDuplicateRequestsDispatchOnlyOneLogicalPayment() throws Exception {
        String merchantId = "postgres-concurrent-" + UUID.randomUUID();
        String idempotencyKey = "postgres-concurrent-key-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setMerchantId(merchantId);
        request.setAmountMinor(12900L);
        request.setCurrency("USD");
        request.setCustomerReference("postgres-concurrent-customer");

        int requestCount = 4;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<PaymentResponse>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent requests did not start together");
                    }
                    return paymentService.createPayment(request, idempotencyKey);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<PaymentResponse> responses = new ArrayList<>();
            for (var future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertEquals(1, responses.stream().map(PaymentResponse::getPaymentId).distinct().count());
            Payment payment = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey).orElseThrow();
            assertEquals(1, paymentRepository.countByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey));
            var attempts = paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(payment);
            assertTrue(!attempts.isEmpty());
            assertEquals(attempts.size(), attempts.stream().map(attempt -> attempt.getProcessorRequestId()).distinct().count());
            long attemptsBeforeReplay = attempts.size();
            paymentService.createPayment(request, idempotencyKey);
            assertEquals(attemptsBeforeReplay, paymentAttemptRepository.findByPaymentOrderByStartedAtAsc(payment).size());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentPostgresRoutesReserveOnlyOneHalfOpenProbe() throws Exception {
            List<Processor> processors = processorRepository.findAll();
            var original = processors.stream().collect(java.util.stream.Collectors.toMap(
                Processor::getId,
                processor -> new ProcessorState(
                    processor.isEnabled(),
                    processor.getCircuitBreakerState(),
                    processor.getCircuitOpenUntil(),
                    processor.isHalfOpenProbeInFlight()
                )
            ));
            Processor recovering = processors.stream()
                .filter(processor -> "Processor A".equals(processor.getName()))
                .findFirst()
                .orElseThrow();

            try {
                processors.forEach(processor -> processor.setEnabled(processor.getId().equals(recovering.getId())));
                recovering.setCircuitBreakerState(CircuitBreakerState.OPEN);
                recovering.setCircuitOpenUntil(java.time.Instant.now().minusSeconds(1));
                recovering.setHalfOpenProbeInFlight(false);
                processorRepository.saveAllAndFlush(processors);

                int requestCount = 2;
                var executor = java.util.concurrent.Executors.newFixedThreadPool(requestCount);
                CountDownLatch ready = new CountDownLatch(requestCount);
                CountDownLatch start = new CountDownLatch(1);
                try {
                    List<java.util.concurrent.Future<java.util.Optional<Processor>>> futures = new ArrayList<>();
                    for (int index = 0; index < requestCount; index++) {
                        futures.add(executor.submit(() -> {
                            ready.countDown();
                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Concurrent probe requests did not start together");
                            }
                            return processorRouter.selectProcessor(ProcessorRoutingStrategy.HEALTH_AWARE, Set.of());
                        }));
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                    start.countDown();
                    long selectedCount = 0;
                    for (var future : futures) {
                        if (future.get(20, TimeUnit.SECONDS).isPresent()) {
                            selectedCount++;
                        }
                    }

                    assertEquals(1, selectedCount);
                    Processor persisted = processorRepository.findById(recovering.getId()).orElseThrow();
                    assertEquals(CircuitBreakerState.HALF_OPEN, persisted.getCircuitBreakerState());
                    assertTrue(persisted.isHalfOpenProbeInFlight());
                } finally {
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                processors.forEach(processor -> {
                    ProcessorState state = original.get(processor.getId());
                    processor.setEnabled(state.enabled());
                    processor.setCircuitBreakerState(state.breakerState());
                    processor.setCircuitOpenUntil(state.openUntil());
                    processor.setHalfOpenProbeInFlight(state.probeInFlight());
                });
                processorRepository.saveAll(processors);
            }
        }

    private record ProcessorState(
        boolean enabled,
        CircuitBreakerState breakerState,
        java.time.Instant openUntil,
        boolean probeInFlight
    ) { }
}
