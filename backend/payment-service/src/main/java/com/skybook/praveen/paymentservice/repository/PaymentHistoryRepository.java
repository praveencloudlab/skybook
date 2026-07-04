package com.skybook.praveen.paymentservice.repository;

import com.skybook.praveen.paymentservice.entity.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    List<PaymentHistory> findByPaymentIdOrderByChangedAtAsc(Long paymentId);
}
