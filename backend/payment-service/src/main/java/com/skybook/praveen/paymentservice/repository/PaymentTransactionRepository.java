package com.skybook.praveen.paymentservice.repository;

import com.skybook.praveen.paymentservice.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByPaymentIdOrderByOccurredAtAsc(Long paymentId);

    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);
}
