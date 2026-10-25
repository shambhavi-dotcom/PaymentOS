package com.paymentos.controller;

import com.paymentos.dto.CreatePaymentRequest;
import com.paymentos.dto.PaymentReconcileRequest;
import com.paymentos.dto.PaymentResponse;
import com.paymentos.entity.Payment;
import com.paymentos.entity.PaymentAttempt;
import com.paymentos.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> createPayment(
        @Valid @RequestBody CreatePaymentRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        PaymentResponse response = paymentService.createPayment(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/payments")
    public List<PaymentResponse> listPayments() {
        return paymentService.listPayments().stream()
            .map(paymentService::toResponse)
            .collect(Collectors.toList());
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long id) {
        return paymentService.findById(id)
            .map(payment -> ResponseEntity.ok(paymentService.toResponse(payment)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/payments/{id}/attempts")
    public List<PaymentAttempt> getAttempts(@PathVariable Long id) {
        return paymentService.findAttempts(id);
    }

    @PostMapping("/payments/{id}/reconcile")
    public ResponseEntity<PaymentResponse> reconcilePayment(
        @PathVariable Long id,
        @Valid @RequestBody PaymentReconcileRequest request
    ) {
        PaymentResponse response = paymentService.reconcilePayment(id, request.getReason());
        return ResponseEntity.ok(response);
    }
}
