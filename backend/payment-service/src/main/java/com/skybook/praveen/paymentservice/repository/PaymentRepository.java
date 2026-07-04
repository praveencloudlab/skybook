package com.skybook.praveen.paymentservice.repository;

import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Optional<Payment> findByBookingId(Long bookingId);

    boolean existsByBookingId(Long bookingId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByStatus(PaymentStatus status);
}
