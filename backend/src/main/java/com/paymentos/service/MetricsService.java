package com.paymentos.service;

import com.paymentos.entity.Payment;
import com.paymentos.entity.PaymentStatus;
import com.paymentos.repository.PaymentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MetricsService {

    private final PaymentRepository paymentRepository;

    public MetricsService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public long countTotalPayments() {
        return paymentRepository.count();
    }

    public long countSuccessfulPayments() {
        return paymentRepository.countByStatus(PaymentStatus.SUCCEEDED);
    }

    public long countFailedPayments() {
        return paymentRepository.countByStatus(PaymentStatus.FAILED);
    }

    public long countUnknownPayments() {
        return paymentRepository.countByStatus(PaymentStatus.UNKNOWN);
    }

    public List<Payment> listPayments() {
        return paymentRepository.findAll();
    }
}
