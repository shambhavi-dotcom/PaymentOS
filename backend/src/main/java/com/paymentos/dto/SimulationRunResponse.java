package com.paymentos.dto;

import com.paymentos.entity.PaymentStatus;
import com.paymentos.service.ProcessorRoutingStrategy;

import java.util.Map;

public record SimulationRunResponse(
    int requestedCount,
    int completedCount,
    long elapsedMs,
    ProcessorRoutingStrategy routingStrategy,
    Map<PaymentStatus, Long> paymentStatusCounts
) {
}
