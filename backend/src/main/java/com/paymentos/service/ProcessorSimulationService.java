package com.paymentos.service;

import com.paymentos.entity.CircuitBreakerState;
import com.paymentos.entity.PaymentAttemptStatus;
import com.paymentos.entity.Processor;
import org.springframework.stereotype.Service;

@Service
public class ProcessorSimulationService {

    public PaymentAttemptStatus evaluateOutcome(Processor processor, String fingerprint) {
        if (processor == null || !processor.isEnabled()) {
            return PaymentAttemptStatus.FAILED_BEFORE_EXECUTION;
        }

        if (processor.getCircuitBreakerState() == CircuitBreakerState.OPEN) {
            return PaymentAttemptStatus.TIMEOUT_UNKNOWN;
        }

        int bucket = Math.floorMod(fingerprint.hashCode(), 100);
        int failureThreshold = (int) Math.round(processor.getFailureProbability() * 100.0);
        int timeoutThreshold = failureThreshold + (int) Math.round(processor.getTimeoutProbability() * 100.0);
        int declineThreshold = Math.min(100, timeoutThreshold + 8);

        if (bucket < failureThreshold) {
            return PaymentAttemptStatus.FAILED_BEFORE_EXECUTION;
        }
        if (bucket < timeoutThreshold) {
            return PaymentAttemptStatus.TIMEOUT_UNKNOWN;
        }
        if (bucket < declineThreshold) {
            return PaymentAttemptStatus.DECLINED;
        }
        return PaymentAttemptStatus.SUCCESS;
    }

    public PaymentAttemptStatus reconcileUnknown(Processor processor, String processorRequestId) {
        if (processor == null || processorRequestId == null || processorRequestId.isBlank()) {
            throw new IllegalArgumentException("Processor and original processor request ID are required");
        }

        int resultBucket = Math.floorMod((processor.getName() + ":" + processorRequestId + ":result").hashCode(), 100);
        return resultBucket < 8 ? PaymentAttemptStatus.DECLINED : PaymentAttemptStatus.SUCCESS;
    }

    public long computeProcessingTime(Processor processor) {
        if (processor == null) {
            return 150L;
        }
        return Math.max(50L, processor.getBaseLatencyMs() + (Math.abs(processor.getName().hashCode()) % 35L));
    }
}
