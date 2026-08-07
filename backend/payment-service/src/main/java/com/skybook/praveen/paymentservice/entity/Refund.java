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
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "refunds",
        indexes = {
                @Index(name = "ix_refunds_payment", columnList = "payment_id")
        },
        // Once per cause per payment (IDEMPOTENCY_MODULE.md §3.6). Postgres
        // treats NULLs as DISTINCT in a unique index, so hand-raised refunds
        // (source_reference NULL) never collide - the same behaviour as the
        // migration's partial "WHERE source_reference IS NOT NULL" index, and
        // this declared form is what the ddl-auto JPA tests build from.
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_refunds_payment_source",
                        columnNames = {"payment_id", "source_reference"})
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

    /**
     * The CAUSE this refund answers, unique per payment when present
     * (IDEMPOTENCY_MODULE.md §3.6) - e.g. {@code cancel:77} for a whole-booking
     * cancellation, {@code partial:77:12,14} naming the cancelled passenger
     * rows. A redelivered Kafka event derives the same value, so the partial
     * unique index (V3) refuses the second insert: the database enforces
     * once-per-cause where a status guard demonstrably could not - the first
     * partial refund left the payment in exactly the state the guard accepted.
     * Null for hand-raised desk refunds, which have no event behind them.
     */
    @Column(name = "source_reference", updatable = false, length = 120)
    private String sourceReference;

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
