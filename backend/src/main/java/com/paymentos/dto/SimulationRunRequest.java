package com.paymentos.dto;

import com.paymentos.service.ProcessorRoutingStrategy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

public class SimulationRunRequest {
    @NotNull
    @Min(1)
    @Max(500)
    private Integer count;

    @NotNull
    private ProcessorRoutingStrategy routingStrategy;

    private Long processorId;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double failureProbability;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double timeoutProbability;

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public ProcessorRoutingStrategy getRoutingStrategy() {
        return routingStrategy;
    }

    public void setRoutingStrategy(ProcessorRoutingStrategy routingStrategy) {
        this.routingStrategy = routingStrategy;
    }

    public Long getProcessorId() {
        return processorId;
    }

    public void setProcessorId(Long processorId) {
        this.processorId = processorId;
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
