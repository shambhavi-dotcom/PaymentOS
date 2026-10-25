package com.paymentos.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public class ProcessorSimulationConfigRequest {
    private Boolean enabled;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double failureProbability;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double timeoutProbability;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Double getFailureProbability() {
        return failureProbability;
    }

    public void setFailureProbability(Double failureProbability) {
        this.failureProbability = failureProbability;
    }

    public Double getTimeoutProbability() {
        return timeoutProbability;
    }

    public void setTimeoutProbability(Double timeoutProbability) {
        this.timeoutProbability = timeoutProbability;
    }
}
