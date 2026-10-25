package com.paymentos.repository;

import com.paymentos.entity.Payment;
import com.paymentos.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
    List<PaymentEvent> findByPaymentOrderByCreatedAtAsc(Payment payment);
}
