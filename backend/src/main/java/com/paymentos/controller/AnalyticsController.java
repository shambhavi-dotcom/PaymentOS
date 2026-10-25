package com.paymentos.controller;

import com.paymentos.repository.PaymentAttemptRepository;
import com.paymentos.repository.RoutingAnalyticsRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final PaymentAttemptRepository paymentAttemptRepository;

    public AnalyticsController(PaymentAttemptRepository paymentAttemptRepository) {
        this.paymentAttemptRepository = paymentAttemptRepository;
    }

    @GetMapping("/routing")
    public List<RoutingAnalyticsRow> getRoutingAnalytics() {
        return paymentAttemptRepository.summarizeByRoutingStrategy();
    }
}
