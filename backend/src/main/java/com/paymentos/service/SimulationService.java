package com.paymentos.service;

import com.paymentos.dto.CreatePaymentRequest;
import com.paymentos.dto.SimulationRunRequest;
import com.paymentos.dto.SimulationRunResponse;
import com.paymentos.entity.PaymentStatus;
import com.paymentos.entity.Processor;
import com.paymentos.repository.ProcessorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SimulationService {
    private final PaymentService paymentService;
    private final ProcessorRepository processorRepository;

    public SimulationService(PaymentService paymentService, ProcessorRepository processorRepository) {
        this.paymentService = paymentService;
        this.processorRepository = processorRepository;
    }

    public SimulationRunResponse run(SimulationRunRequest request) {
        applyFailureInjection(request);
        long startedAt = System.nanoTime();
        Map<PaymentStatus, Long> counts = new EnumMap<>(PaymentStatus.class);

        for (int i = 0; i < request.getCount(); i++) {
            CreatePaymentRequest payment = new CreatePaymentRequest();
            payment.setMerchantId("simulation");
            payment.setAmountMinor(100L + i);
            payment.setCurrency("USD");
            payment.setCustomerReference("simulation-" + UUID.randomUUID());
            PaymentStatus status = paymentService.createPayment(
                payment,
                "sim-" + UUID.randomUUID(),
                request.getRoutingStrategy()
            ).getStatus();
            counts.merge(status, 1L, Long::sum);
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return new SimulationRunResponse(request.getCount(), request.getCount(), elapsedMs, request.getRoutingStrategy(), counts);
    }

    private void applyFailureInjection(SimulationRunRequest request) {
        boolean settingFailure = request.getFailureProbability() != null;
        boolean settingTimeout = request.getTimeoutProbability() != null;
        if (!settingFailure && !settingTimeout) {
            return;
        }
        if (request.getProcessorId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "processorId is required to inject processor failures");
        }

        Processor processor = processorRepository.findById(request.getProcessorId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Processor not found"));
        double effectiveFailure = request.getFailureProbability() == null
            ? processor.getFailureProbability()
            : request.getFailureProbability();
        double effectiveTimeout = request.getTimeoutProbability() == null
            ? processor.getTimeoutProbability()
            : request.getTimeoutProbability();
        if (effectiveFailure + effectiveTimeout > 1.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failure and timeout probabilities must sum to 1.0 or less");
        }
        if (settingFailure) {
            processor.setFailureProbability(request.getFailureProbability());
        }
        if (settingTimeout) {
            processor.setTimeoutProbability(request.getTimeoutProbability());
        }
        processorRepository.save(processor);
    }
}
