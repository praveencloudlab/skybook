package com.skybook.praveen.paymentservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One refund against a captured payment. amount + cancellationFee together
 * equal the fare portion being cancelled - the fee is stored explicitly so
 * invoices/emails can show what was withheld (design doc section 3.3).
 */
@Entity
@Table(name = "refunds", indexes = {
        @Index(name = "ix_refunds_payment", columnList = "payment_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    // e.g. "REF-2026-L3Q9XE" (PaymentReferenceGenerator).
    @Column(nullable = false, unique = true, updatable = false, length = 20)
    private String refundReference;

    // Amount returned to the customer - computed by RefundCalculator.
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    // The withheld portion (SAVER cancellation fee etc.).
    @Column(nullable = false, updatable = false)
    private BigDecimal cancellationFee;

    @Column(updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private RefundStatus status;

    @Column
    private LocalDateTime completedAt;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = RefundStatus.PENDING;
        }
        if (cancellationFee == null) {
            cancellationFee = BigDecimal.ZERO;
        }
    }
}
