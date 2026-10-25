package com.paymentos.service;

public enum ProcessorRoutingStrategy {
    ROUND_ROBIN,
    HEALTH_AWARE,
    LATENCY_AWARE
}
