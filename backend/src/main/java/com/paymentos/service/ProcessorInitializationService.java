package com.paymentos.service;

import com.paymentos.entity.CircuitBreakerState;
import com.paymentos.entity.Processor;
import com.paymentos.repository.ProcessorRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessorInitializationService {

    private final ProcessorRepository processorRepository;

    public ProcessorInitializationService(ProcessorRepository processorRepository) {
        this.processorRepository = processorRepository;
    }

    @PostConstruct
    @Transactional
    public void initialize() {
        if (processorRepository.count() > 0) {
            return;
        }

        Processor a = new Processor("Processor A", 120, 0.15, 0.08);
        a.setRecentSuccessRate(0.96);
        a.setRecentLatencyMs(140);
        a.setCircuitBreakerState(CircuitBreakerState.CLOSED);
        processorRepository.save(a);

        Processor b = new Processor("Processor B", 170, 0.10, 0.12);
        b.setRecentSuccessRate(0.92);
        b.setRecentLatencyMs(180);
        b.setCircuitBreakerState(CircuitBreakerState.CLOSED);
        processorRepository.save(b);

        Processor c = new Processor("Processor C", 220, 0.20, 0.16);
        c.setRecentSuccessRate(0.88);
        c.setRecentLatencyMs(240);
        c.setCircuitBreakerState(CircuitBreakerState.CLOSED);
        processorRepository.save(c);
    }
}
