package com.paymentos.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processors", indexes = {
    @Index(name = "idx_processors_enabled", columnList = "enabled"),
    @Index(name = "idx_processors_name", columnList = "name", unique = true)
})
public class Processor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "base_latency_ms", nullable = false)
    private int baseLatencyMs;

    @Column(name = "failure_probability", nullable = false)
    private double failureProbability;

    @Column(name = "timeout_probability", nullable = false)
    private double timeoutProbability;

    @Column(name = "recent_success_rate", nullable = false)
    private double recentSuccessRate = 1.0;

    @Column(name = "recent_latency_ms", nullable = false)
    private long recentLatencyMs = 150L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "circuit_state", nullable = false, length = 20)
    private CircuitBreakerState circuitBreakerState = CircuitBreakerState.CLOSED;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "circuit_open_until")
    private Instant circuitOpenUntil;

    @Column(name = "half_open_probe_in_flight", nullable = false)
    private boolean halfOpenProbeInFlight;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Processor() {
    }

    public Processor(String name, int baseLatencyMs, double failureProbability, double timeoutProbability) {
        this.name = name;
        this.baseLatencyMs = baseLatencyMs;
        this.failureProbability = failureProbability;
        this.timeoutProbability = timeoutProbability;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBaseLatencyMs() {
        return baseLatencyMs;
    }

    public void setBaseLatencyMs(int baseLatencyMs) {
        this.baseLatencyMs = baseLatencyMs;
    }

    public double getFailureProbability() {
        return failureProbability;
    }

    public void setFailureProbability(double failureProbability) {
        this.failureProbability = failureProbability;
    }

    public double getTimeoutProbability() {
        return timeoutProbability;
    }

    public void setTimeoutProbability(double timeoutProbability) {
        this.timeoutProbability = timeoutProbability;
    }

    public double getRecentSuccessRate() {
        return recentSuccessRate;
    }

    public void setRecentSuccessRate(double recentSuccessRate) {
        this.recentSuccessRate = recentSuccessRate;
    }

    public long getRecentLatencyMs() {
        return recentLatencyMs;
    }

    public void setRecentLatencyMs(long recentLatencyMs) {
        this.recentLatencyMs = recentLatencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public CircuitBreakerState getCircuitBreakerState() {
        return circuitBreakerState;
    }

    public void setCircuitBreakerState(CircuitBreakerState circuitBreakerState) {
        this.circuitBreakerState = circuitBreakerState;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public void setLastFailureAt(Instant lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Instant getCircuitOpenUntil() {
        return circuitOpenUntil;
    }

    public void setCircuitOpenUntil(Instant circuitOpenUntil) {
        this.circuitOpenUntil = circuitOpenUntil;
    }

    public boolean isHalfOpenProbeInFlight() {
        return halfOpenProbeInFlight;
    }

    public void setHalfOpenProbeInFlight(boolean halfOpenProbeInFlight) {
        this.halfOpenProbeInFlight = halfOpenProbeInFlight;
    }
}
