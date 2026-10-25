package com.paymentos.controller;

import com.paymentos.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalPayments", metricsService.countTotalPayments());
        response.put("successfulPayments", metricsService.countSuccessfulPayments());
        response.put("failedPayments", metricsService.countFailedPayments());
        response.put("unknownPayments", metricsService.countUnknownPayments());
        return response;
    }
}
