package com.paymentos.repository;

import com.paymentos.entity.Payment;
import com.paymentos.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    List<PaymentAttempt> findByPaymentOrderByStartedAtAsc(Payment payment);
    Optional<PaymentAttempt> findFirstByPaymentOrderByStartedAtDescIdDesc(Payment payment);

    @Query("select a from PaymentAttempt a left join fetch a.processor")
    List<PaymentAttempt> findAllWithProcessor();

    @Query("""
        select p.routingStrategy as strategy,
               count(a) as attemptCount,
               sum(case when a.status = com.paymentos.entity.PaymentAttemptStatus.SUCCESS then 1 else 0 end) as successfulAttemptCount,
               avg(a.durationMs) as averageLatencyMs
        from PaymentAttempt a join a.payment p
        group by p.routingStrategy
        order by p.routingStrategy
        """)
    List<RoutingAnalyticsRow> summarizeByRoutingStrategy();
}
