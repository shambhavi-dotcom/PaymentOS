package com.paymentos.dto;

import com.paymentos.entity.PaymentStatus;
import java.time.Instant;

public class PaymentResponse {
    private String paymentId;
    private PaymentStatus status;
    private Long amountMinor;
    private String currency;
    private String processor;
    private Long processingTimeMs;
    private String merchantId;
    private String routingStrategy;
    private Instant createdAt;

    public PaymentResponse() {
    }

    public PaymentResponse(String paymentId, PaymentStatus status, Long amountMinor, String currency, String processor, Long processingTimeMs) {
        this.paymentId = paymentId;
        this.status = status;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.processor = processor;
        this.processingTimeMs = processingTimeMs;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getProcessor() {
        return processor;
    }

    public void setProcessor(String processor) {
        this.processor = processor;
    }

    public Long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(Long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getRoutingStrategy() {
        return routingStrategy;
    }

    public void setRoutingStrategy(String routingStrategy) {
        this.routingStrategy = routingStrategy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
