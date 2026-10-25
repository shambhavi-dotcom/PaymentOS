package com.paymentos.service;

import com.paymentos.entity.Payment;
import com.paymentos.entity.PaymentStatus;

import java.util.EnumSet;
import java.util.Map;

public final class PaymentStateTransition {
    private static final Map<PaymentStatus, EnumSet<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
        PaymentStatus.CREATED, EnumSet.of(PaymentStatus.PROCESSING, PaymentStatus.FAILED),
        PaymentStatus.PROCESSING, EnumSet.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.UNKNOWN),
        PaymentStatus.UNKNOWN, EnumSet.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED),
        PaymentStatus.SUCCEEDED, EnumSet.noneOf(PaymentStatus.class),
        PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class)
    );

    private PaymentStateTransition() {
    }

    public static void validate(Payment payment, PaymentStatus newStatus) {
        EnumSet<PaymentStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(payment.getStatus(), EnumSet.noneOf(PaymentStatus.class));
        if (!allowed.contains(newStatus)) {
            throw new IllegalStateException("Invalid payment state transition from " + payment.getStatus() + " to " + newStatus);
        }
    }
}
