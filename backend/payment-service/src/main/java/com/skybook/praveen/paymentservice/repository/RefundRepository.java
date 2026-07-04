package com.skybook.praveen.paymentservice.repository;

import com.skybook.praveen.paymentservice.entity.Refund;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentId(Long paymentId);

    Optional<Refund> findByRefundReference(String refundReference);

    List<Refund> findByStatus(RefundStatus status);
}
