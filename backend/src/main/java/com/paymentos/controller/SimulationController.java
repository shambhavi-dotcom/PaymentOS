package com.paymentos.controller;

import com.paymentos.dto.SimulationRunRequest;
import com.paymentos.dto.SimulationRunResponse;
import com.paymentos.service.SimulationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulations")
public class SimulationController {
    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/run")
    public SimulationRunResponse run(@Valid @RequestBody SimulationRunRequest request) {
        return simulationService.run(request);
    }
}
