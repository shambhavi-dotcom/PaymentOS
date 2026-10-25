package com.paymentos.service;

import com.paymentos.dto.CreatePaymentRequest;
import com.paymentos.dto.PaymentResponse;
import com.paymentos.entity.*;
import com.paymentos.repository.PaymentAttemptRepository;
import com.paymentos.repository.PaymentEventRepository;
import com.paymentos.repository.PaymentRepository;
import com.paymentos.repository.ProcessorRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ProcessorRepository processorRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ProcessorSimulationService processorSimulationService;
    private final PaymentCreationTransaction paymentCreationTransaction;

    public PaymentService(
        PaymentRepository paymentRepository,
        ProcessorRepository processorRepository,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentEventRepository paymentEventRepository,
        ProcessorSimulationService processorSimulationService,
        PaymentCreationTransaction paymentCreationTransaction
    ) {
        this.paymentRepository = paymentRepository;
        this.processorRepository = processorRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.processorSimulationService = processorSimulationService;
        this.paymentCreationTransaction = paymentCreationTransaction;
    }

    public PaymentResponse createPayment(CreatePaymentRequest request, String idempotencyKey) {
        return createPayment(request, idempotencyKey, ProcessorRoutingStrategy.HEALTH_AWARE);
    }

    public PaymentResponse createPayment(
        CreatePaymentRequest request,
        String idempotencyKey,
        ProcessorRoutingStrategy routingStrategy
    ) {
        String merchantId = request.getMerchantId().trim();
        String normalizedCurrency = request.getCurrency().trim().toUpperCase();
        String fingerprint = requestFingerprint(merchantId, normalizedCurrency, request.getAmountMinor(), request.getCustomerReference().trim());

        try {
            Optional<Payment> existing = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
            if (existing.isPresent()) {
                return existingResponse(existing.get(), fingerprint);
            }

            Long paymentId = paymentCreationTransaction.create(
                request, idempotencyKey, merchantId, normalizedCurrency, fingerprint, routingStrategy
            );
            Payment created = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Created payment could not be loaded"));
            return toResponse(created);
        } catch (DataIntegrityViolationException ex) {
            Optional<Payment> duplicate = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
            if (duplicate.isPresent()) {
                return existingResponse(duplicate.get(), fingerprint);
            }
            throw ex;
        }
    }

    private PaymentResponse existingResponse(Payment payment, String fingerprint) {
        if (!payment.getRequestFingerprint().equals(fingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused for a different payment payload");
        }
        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public List<Payment> listPayments() {
        return paymentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findById(Long id) {
        return paymentRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<PaymentAttempt> findAttempts(Long paymentId) {
        return paymentRepository.findById(paymentId)
            .map(paymentAttemptRepository::findByPaymentOrderByStartedAtAsc)
            .orElse(List.of());
    }

    @Transactional
    public PaymentResponse reconcilePayment(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only UNKNOWN payments can be reconciled");
        }

        PaymentAttempt attempt = paymentAttemptRepository.findFirstByPaymentOrderByStartedAtDescIdDesc(payment)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Unknown payment has no processor attempt to reconcile"));
        if (attempt.getStatus() != PaymentAttemptStatus.TIMEOUT_UNKNOWN || attempt.getProcessor() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The latest processor attempt cannot be authoritatively reconciled");
        }

        PaymentAttemptStatus processorOutcome = processorSimulationService.reconcileUnknown(
            attempt.getProcessor(),
            attempt.getProcessorRequestId()
        );
        PaymentStatus finalStatus = processorOutcome == PaymentAttemptStatus.SUCCESS
            ? PaymentStatus.SUCCEEDED
            : PaymentStatus.FAILED;
        PaymentStateTransition.validate(payment, finalStatus);
        attempt.setStatus(processorOutcome);
        attempt.setFailureReason(processorOutcome == PaymentAttemptStatus.DECLINED
            ? "processor confirmed a decline during reconciliation"
            : null);
        paymentAttemptRepository.save(attempt);

        String description = "Processor request " + attempt.getProcessorRequestId() + " reconciled as " + finalStatus;
        if (reason != null && !reason.isBlank()) {
            description += ": " + reason.trim();
        }
        payment.setStatus(finalStatus);
        payment.setCompletedAt(Instant.now());
        appendEvent(payment, "PAYMENT_RECONCILED", PaymentStatus.UNKNOWN, finalStatus, description);
        paymentRepository.save(payment);
        return toResponse(payment);
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

    private String requestFingerprint(String merchantId, String currency, Long amountMinor, String customerReference) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = merchantId + "|" + currency + "|" + amountMinor + "|" + customerReference;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    public PaymentResponse toResponse(Payment payment) {
        Processor processor = payment.getSelectedProcessorId() == null
            ? null
            : processorRepository.findByName(payment.getSelectedProcessorId()).orElse(null);
        Long processingTimeMs = paymentAttemptRepository.findFirstByPaymentOrderByStartedAtDescIdDesc(payment)
            .map(PaymentAttempt::getDurationMs)
            .orElse(null);
        PaymentResponse response = new PaymentResponse(
            "pay_" + payment.getId(),
            payment.getStatus(),
            payment.getAmountMinor(),
            payment.getCurrency(),
            payment.getSelectedProcessorId(),
            processingTimeMs
        );
        response.setMerchantId(payment.getMerchantId());
        response.setRoutingStrategy(payment.getRoutingStrategy());
        response.setCreatedAt(payment.getCreatedAt());
        return response;
    }
}
