package com.paymentos.service;

import com.paymentos.dto.CreatePaymentRequest;
import com.paymentos.entity.*;
import com.paymentos.repository.PaymentAttemptRepository;
import com.paymentos.repository.PaymentEventRepository;
import com.paymentos.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentCreationTransaction {
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ProcessorRouter processorRouter;
    private final ProcessorSimulationService processorSimulationService;

    public PaymentCreationTransaction(
        PaymentRepository paymentRepository,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentEventRepository paymentEventRepository,
        ProcessorRouter processorRouter,
        ProcessorSimulationService processorSimulationService
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.processorRouter = processorRouter;
        this.processorSimulationService = processorSimulationService;
    }

    @Transactional
    public Long create(
        CreatePaymentRequest request,
        String idempotencyKey,
        String merchantId,
        String currency,
        String fingerprint,
        ProcessorRoutingStrategy strategy
    ) {
        Payment payment = new Payment();
        payment.setMerchantId(merchantId);
        payment.setIdempotencyKey(idempotencyKey);
        payment.setRequestFingerprint(fingerprint);
        payment.setAmountMinor(request.getAmountMinor());
        payment.setCurrency(currency);
        payment.setCustomerReference(request.getCustomerReference().trim());
        payment.setRoutingStrategy(strategy.name());
        payment.setStatus(PaymentStatus.CREATED);
        payment = paymentRepository.saveAndFlush(payment);

        appendEvent(payment, "PAYMENT_CREATED", null, PaymentStatus.CREATED, "Payment created and awaiting processor selection");

        var selected = processorRouter.selectProcessor(strategy, Set.of());
        if (selected.isEmpty()) {
            transition(payment, PaymentStatus.FAILED, "No processors were available; payment was not dispatched");
            paymentRepository.save(payment);
            return payment.getId();
        }

        Processor selectedProcessor = selected.get();
        payment.setSelectedProcessorId(selectedProcessor.getName());
        transition(payment, PaymentStatus.PROCESSING, "Payment dispatched to processor " + selectedProcessor.getName());

        Set<String> attemptedProcessors = new HashSet<>();
        attemptedProcessors.add(selectedProcessor.getName());
        PaymentAttemptStatus outcome = executeAttempt(payment, selectedProcessor, fingerprint);
        for (int retry = 0; retry < 2 && outcome == PaymentAttemptStatus.FAILED_BEFORE_EXECUTION; retry++) {
            var alternate = processorRouter.selectProcessor(ProcessorRoutingStrategy.LATENCY_AWARE, attemptedProcessors);
            if (alternate.isEmpty()) {
                break;
            }
            selectedProcessor = alternate.get();
            attemptedProcessors.add(selectedProcessor.getName());
            payment.setSelectedProcessorId(selectedProcessor.getName());
            appendEvent(payment, "PAYMENT_RETRY_DISPATCHED", PaymentStatus.PROCESSING, PaymentStatus.PROCESSING,
                "Retrying after confirmed pre-execution failure with processor " + selectedProcessor.getName());
            outcome = executeAttempt(payment, selectedProcessor, fingerprint);
        }

        switch (outcome) {
            case SUCCESS -> transition(payment, PaymentStatus.SUCCEEDED, "Processor accepted the payment");
            case DECLINED -> transition(payment, PaymentStatus.FAILED, "Processor declined the payment");
            case FAILED_BEFORE_EXECUTION -> transition(payment, PaymentStatus.FAILED, "Processor attempts failed before execution");
            case TIMEOUT_UNKNOWN -> transition(payment, PaymentStatus.UNKNOWN, "Payment outcome is uncertain; awaiting processor reconciliation");
        }
        paymentRepository.save(payment);
        return payment.getId();
    }

    private PaymentAttemptStatus executeAttempt(Payment payment, Processor processor, String fingerprint) {
        PaymentAttemptStatus outcome = processorSimulationService.evaluateOutcome(processor, fingerprint);
        long processingTime = processorSimulationService.computeProcessingTime(processor);

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setPayment(payment);
        attempt.setProcessor(processor);
        attempt.setProcessorRequestId(UUID.randomUUID().toString());
        attempt.setStartedAt(Instant.now());
        attempt.setStatus(outcome);
        attempt.setCompletedAt(Instant.now());
        attempt.setDurationMs(processingTime);
        attempt.setFailureReason(switch (outcome) {
            case TIMEOUT_UNKNOWN -> "processor timed out before a final result was known";
            case DECLINED -> "processor declined the payment";
            case FAILED_BEFORE_EXECUTION -> "processor failed before executing the payment";
            case SUCCESS -> null;
        });
        paymentAttemptRepository.save(attempt);
        processorRouter.recordOutcome(processor, outcome, processingTime);
        return outcome;
    }

    private void transition(Payment payment, PaymentStatus nextStatus, String description) {
        PaymentStatus previousStatus = payment.getStatus();
        PaymentStateTransition.validate(payment, nextStatus);
        payment.setStatus(nextStatus);
        if (nextStatus == PaymentStatus.SUCCEEDED || nextStatus == PaymentStatus.FAILED || nextStatus == PaymentStatus.UNKNOWN) {
            payment.setCompletedAt(Instant.now());
        }
        appendEvent(payment, "PAYMENT_STATUS_CHANGED", previousStatus, nextStatus, description);
    }

    private void appendEvent(Payment payment, String eventType, PaymentStatus previousStatus, PaymentStatus newStatus, String description) {
        PaymentEvent event = new PaymentEvent();
        event.setPayment(payment);
        event.setEventType(eventType);
        event.setPreviousStatus(previousStatus);
        event.setNewStatus(newStatus);
        event.setDescription(description);
        paymentEventRepository.save(event);
    }
}
