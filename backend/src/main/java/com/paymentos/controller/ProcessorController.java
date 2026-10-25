package com.paymentos.controller;

import com.paymentos.dto.ProcessorSimulationConfigRequest;
import com.paymentos.entity.Processor;
import com.paymentos.repository.ProcessorRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ProcessorController {

    private final ProcessorRepository processorRepository;

    public ProcessorController(ProcessorRepository processorRepository) {
        this.processorRepository = processorRepository;
    }

    @GetMapping("/processors")
    public List<Processor> getProcessors() {
        return processorRepository.findAll();
    }

    @PutMapping("/processors/{id}/simulation")
    public Processor updateSimulationConfig(
        @PathVariable Long id,
        @Valid @RequestBody ProcessorSimulationConfigRequest request
    ) {
        if (request.getFailureProbability() + request.getTimeoutProbability() > 1.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failure and timeout probabilities must sum to 1.0 or less");
        }
        Processor processor = processorRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Processor not found"));
        if (request.getEnabled() != null) {
            processor.setEnabled(request.getEnabled());
        }
        processor.setFailureProbability(request.getFailureProbability());
        processor.setTimeoutProbability(request.getTimeoutProbability());
        return processorRepository.save(processor);
    }
}
