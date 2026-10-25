package com.paymentos;

import com.paymentos.entity.CircuitBreakerState;
import com.paymentos.entity.PaymentAttemptStatus;
import com.paymentos.entity.Processor;
import com.paymentos.repository.ProcessorRepository;
import com.paymentos.service.ProcessorRouter;
import com.paymentos.service.ProcessorSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
class ProcessorRouterTest {

    @Autowired
    private ProcessorRepository processorRepository;

    @Autowired
    private ProcessorRouter processorRouter;

    @Autowired
    private ProcessorSimulationService processorSimulationService;

    @BeforeEach
    void disableExistingProcessors() {
        processorRepository.findAll().forEach(processor -> {
            processor.setEnabled(false);
            processorRepository.save(processor);
        });
    }

    @Test
    void shouldSkipOpenProcessorsAndChooseClosedOne() {
        Processor open = new Processor("Processor Open", 150, 0.2, 0.2);
        open.setEnabled(true);
        open.setCircuitBreakerState(CircuitBreakerState.OPEN);
        open.setRecentSuccessRate(0.15);
        processorRepository.save(open);

        Processor closed = new Processor("Processor Closed", 100, 0.1, 0.1);
        closed.setEnabled(true);
        closed.setCircuitBreakerState(CircuitBreakerState.CLOSED);
        closed.setRecentSuccessRate(0.93);
        processorRepository.save(closed);

        Processor chosen = processorRouter.selectProcessor();

        assertNotNull(chosen);
        assertEquals("Processor Closed", chosen.getName());
    }

    @Test
    void shouldOpenProcessorAfterRepeatedFailures() {
        Processor processor = new Processor("Processor Failing", 160, 0.15, 0.15);
        processor.setEnabled(true);
        processor.setRecentSuccessRate(0.90);
        processor.setCircuitBreakerState(CircuitBreakerState.CLOSED);
        processorRepository.save(processor);

        processorRouter.recordOutcome(processor, PaymentAttemptStatus.FAILED_BEFORE_EXECUTION, 180L);
        processorRouter.recordOutcome(processor, PaymentAttemptStatus.TIMEOUT_UNKNOWN, 210L);
        processorRouter.recordOutcome(processor, PaymentAttemptStatus.FAILED_BEFORE_EXECUTION, 230L);

        assertEquals(CircuitBreakerState.OPEN, processor.getCircuitBreakerState());
    }

    @Test
    void shouldExcludeDisabledProcessorsAndReserveOnlyOneHalfOpenProbe() {
        Processor disabled = new Processor("Processor Disabled", 150, 0.1, 0.1);
        disabled.setEnabled(false);
        processorRepository.save(disabled);

        Processor halfOpen = new Processor("Processor Half Open", 100, 0.1, 0.1);
        halfOpen.setEnabled(true);
        halfOpen.setCircuitBreakerState(CircuitBreakerState.HALF_OPEN);
        processorRepository.saveAndFlush(halfOpen);

        Processor selected = processorRouter.selectProcessor();
        assertEquals(halfOpen.getName(), selected.getName());
        assertEquals(true, selected.isHalfOpenProbeInFlight());

        assertThrows(ResponseStatusException.class, processorRouter::selectProcessor);
    }

    @Test
    void shouldNotRouteWhenEveryProcessorCircuitIsOpenOrDisabled() {
        Processor open = new Processor("Processor Open Only", 150, 0.1, 0.1);
        open.setEnabled(true);
        open.setCircuitBreakerState(CircuitBreakerState.OPEN);
        open.setCircuitOpenUntil(java.time.Instant.now().plusSeconds(60));
        processorRepository.saveAndFlush(open);

        assertThrows(ResponseStatusException.class, processorRouter::selectProcessor);
    }

    @Test
    void shouldAutomaticallyMoveExpiredOpenCircuitToSingleHalfOpenProbe() {
        Processor recovering = new Processor("Processor Recovering", 100, 0.1, 0.1);
        recovering.setEnabled(true);
        recovering.setCircuitBreakerState(CircuitBreakerState.OPEN);
        recovering.setCircuitOpenUntil(java.time.Instant.now().minusSeconds(1));
        processorRepository.saveAndFlush(recovering);

        Processor probe = processorRouter.selectProcessor();

        assertEquals(recovering.getName(), probe.getName());
        assertEquals(CircuitBreakerState.HALF_OPEN, probe.getCircuitBreakerState());
        assertTrue(probe.isHalfOpenProbeInFlight());
        assertThrows(ResponseStatusException.class, processorRouter::selectProcessor);
    }

    @Test
    void shouldCloseHalfOpenCircuitAfterSuccessfulProbe() {
        Processor recovering = new Processor("Processor Probe Success", 100, 0.1, 0.1);
        recovering.setEnabled(true);
        recovering.setCircuitBreakerState(CircuitBreakerState.HALF_OPEN);
        recovering.setHalfOpenProbeInFlight(true);
        processorRepository.saveAndFlush(recovering);

        processorRouter.recordOutcome(recovering, PaymentAttemptStatus.SUCCESS, 120L);

        assertEquals(CircuitBreakerState.CLOSED, recovering.getCircuitBreakerState());
        assertFalse(recovering.isHalfOpenProbeInFlight());
        assertEquals(null, recovering.getCircuitOpenUntil());
        assertEquals(recovering.getName(), processorRouter.selectProcessor().getName());
    }

    @Test
    void shouldReopenHalfOpenCircuitAfterFailedProbe() {
        Processor recovering = new Processor("Processor Probe Failure", 100, 0.1, 0.1);
        recovering.setEnabled(true);
        recovering.setCircuitBreakerState(CircuitBreakerState.HALF_OPEN);
        recovering.setHalfOpenProbeInFlight(true);
        processorRepository.saveAndFlush(recovering);

        processorRouter.recordOutcome(recovering, PaymentAttemptStatus.TIMEOUT_UNKNOWN, 120L);

        assertEquals(CircuitBreakerState.OPEN, recovering.getCircuitBreakerState());
        assertFalse(recovering.isHalfOpenProbeInFlight());
        assertTrue(recovering.getCircuitOpenUntil().isAfter(java.time.Instant.now()));
        assertThrows(ResponseStatusException.class, processorRouter::selectProcessor);
    }

    @Test
    void configuredFullFailureProbabilityAlwaysFailsBeforeExecution() {
        Processor processor = new Processor("Processor Full Failure", 100, 1.0, 0.0);

        assertEquals(PaymentAttemptStatus.FAILED_BEFORE_EXECUTION,
            processorSimulationService.evaluateOutcome(processor, "fixed-workload-fingerprint"));
    }

    @Test
    void simulatedLatencyDoesNotDriftWithPreviousObservedLatency() {
        Processor processor = new Processor("Processor Stable Latency", 100, 0.0, 0.0);
        processor.setRecentLatencyMs(100);
        long first = processorSimulationService.computeProcessingTime(processor);
        processor.setRecentLatencyMs(100_000);

        assertEquals(first, processorSimulationService.computeProcessingTime(processor));
    }
}
