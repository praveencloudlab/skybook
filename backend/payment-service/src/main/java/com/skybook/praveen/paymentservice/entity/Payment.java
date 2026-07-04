package com.skybook.praveen.paymentservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The Payment aggregate root (design doc section 3.1). Transactions, history
 * and refunds hang off this; the booking lives in booking-service and is
 * referenced by id + PNR only.
 *
 * Aggregate invariants (section 3.1.1) and where they are enforced:
 * - capturedAmount <= amount            (PaymentValidator + state machine)
 * - refundedAmount <= capturedAmount    (PaymentValidator, serialized by @Version)
 * - one payment per booking             (DB unique on bookingId)
 * - references / idempotencyKey unique  (DB unique constraints)
 * - invoice exists iff CAPTURED         (created in the capture transaction)
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Human-readable external correlation id, e.g. "PAY-2026-K7M4Z9"
    // (PaymentReferenceGenerator). Immutable.
    @Column(nullable = false, unique = true, updatable = false, length = 20)
    private String paymentReference;

    // Reference only - the Booking in booking-service. UNIQUE: one payment
    // aggregate per booking (v1 - split payments are a documented non-goal).
    @Column(nullable = false, unique = true, updatable = false)
    private Long bookingId;

    // PNR, denormalized for support/debugging and invoice display.
    @Column(nullable = false, updatable = false, length = 10)
    private String bookingReference;

    // The full amount owed - from BookingEvent.totalFare at creation.
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    // ISO-4217, validated by CurrencyValidator before persistence.
    @Column(nullable = false, length = 3)
    private String currency;

    // Running totals maintained by the service layer inside the same
    // transaction as the ledger rows they summarize.
    @Column(nullable = false)
    private BigDecimal capturedAmount;

    @Column(nullable = false)
    private BigDecimal refundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PaymentMethod method;

    // Client-supplied Idempotency-Key header (design doc section 8).
    // Nullable; unique when present - a replay returns the original payment.
    @Column(unique = true, updatable = false, length = 64)
    private String idempotencyKey;

    // The gateway's id for the authorization - null until authorized.
    @Column(length = 100)
    private String gatewayReference;

    // Gateway message for AUTHORIZATION_FAILED / CAPTURE_FAILED states.
    @Column(length = 255)
    private String failureReason;

    // Snapshot of the booking's per-passenger fare lines at creation time,
    // for fare-type refund rules without a booking-service call
    // (design doc section 16.4). Compact "FARETYPE:amount" pairs joined by
    // ';', e.g. "FLEXI:100.00;SAVER:80.00". Parsed only by RefundCalculator.
    @Column(length = 1000, updatable = false)
    private String fareBreakdown;

    // Append-only ledger of gateway interactions. NO cascade - transaction
    // rows are saved explicitly by the service layer; this mapping exists
    // for reads (design doc section 3.2).
    @OneToMany(mappedBy = "payment", fetch = FetchType.LAZY)
    @OrderBy("occurredAt ASC")
    @Builder.Default
    private List<PaymentTransaction> transactions = new ArrayList<>();

    // Refunds are saved explicitly too; mapping for reads.
    @OneToMany(mappedBy = "payment", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Refund> refunds = new ArrayList<>();

    // Audit trail, written via cascade in the same transaction as each
    // change - same pattern as Booking.history / FlightInventory.history.
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("changedAt ASC")
    @Builder.Default
    private List<PaymentHistory> history = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
        if (method == null) {
            method = PaymentMethod.CARD;
        }
        if (capturedAmount == null) {
            capturedAmount = BigDecimal.ZERO;
        }
        if (refundedAmount == null) {
            refundedAmount = BigDecimal.ZERO;
        }
    }
}
