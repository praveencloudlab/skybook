package com.skybook.praveen.paymentservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
 * Immutable once issued (design doc section 7). Created in the same
 * transaction as the CAPTURED transition - an invoice exists if and only if
 * the payment reached CAPTURED. Refunds never mutate it (credit notes are
 * future work). Money breakdown columns are future-proofing: in v1
 * taxAmount = discount = 0 and subtotal = grandTotal = payment amount.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, unique = true, updatable = false)
    private Payment payment;

    // e.g. "INV-2026-000123" - DB-sequence-backed (InvoiceNumberGenerator).
    @Column(nullable = false, unique = true, updatable = false, length = 20)
    private String invoiceNumber;

    // PNR snapshot for display - the invoice must render without a
    // booking-service call.
    @Column(nullable = false, updatable = false, length = 10)
    private String bookingReference;

    @Column(nullable = false, updatable = false)
    private BigDecimal subtotal;

    @Column(nullable = false, updatable = false)
    private BigDecimal taxAmount;

    @Column(nullable = false, updatable = false)
    private BigDecimal discount;

    @Column(nullable = false, updatable = false)
    private BigDecimal grandTotal;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    // Charge-composition snapshots copied from the Payment at issue time
    // (SEAT_SELECTION_MODULE.md §10) - the invoice renders "fares X + seat
    // selection Y" without recomputing anything. Null on legacy payments.
    @Column(name = "base_fare_total", updatable = false, precision = 19, scale = 2)
    private BigDecimal baseFareTotal;

    @Column(name = "seat_surcharge_total", updatable = false, precision = 19, scale = 2)
    private BigDecimal seatSurchargeTotal;

    @Column(nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @PrePersist
    public void prePersist() {
        if (taxAmount == null) {
            taxAmount = BigDecimal.ZERO;
        }
        if (discount == null) {
            discount = BigDecimal.ZERO;
        }
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }
}
