package com.paymentos.repository;

public interface RoutingAnalyticsRow {
    String getStrategy();
    Long getAttemptCount();
    Long getSuccessfulAttemptCount();
    Double getAverageLatencyMs();
}
