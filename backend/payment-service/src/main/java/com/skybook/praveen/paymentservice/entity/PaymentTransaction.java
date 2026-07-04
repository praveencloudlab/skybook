package com.skybook.praveen.paymentservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.paymentservice.enums.TransactionStatus;
import com.skybook.praveen.paymentservice.enums.TransactionType;
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
 * Append-only ledger: one row per gateway interaction INCLUDING failures
 * (design doc section 3.2). Rows are never updated or deleted - a failed
 * capture is a FAILED CAPTURE row, followed (maybe) by a SUCCEEDED CAPTURE
 * row on retry. Every business column is updatable = false.
 */
@Entity
@Table(name = "payment_transactions", indexes = {
        @Index(name = "ix_payment_transactions_payment", columnList = "payment_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    // e.g. "TXN-2026-A8P1W2" (PaymentReferenceGenerator).
    @Column(nullable = false, unique = true, updatable = false, length = 20)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private TransactionStatus status;

    // Amount attempted in this interaction.
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    // Stored verbatim from the gateway, never parsed for business logic.
    @Column(updatable = false, length = 100)
    private String gatewayReference;

    @Column(updatable = false, length = 30)
    private String gatewayResponseCode;

    @Column(updatable = false, length = 255)
    private String gatewayMessage;

    // Full raw gateway response for troubleshooting (TEXT; jsonb when a
    // real gateway lands - design doc section 3.2).
    @Column(updatable = false, columnDefinition = "text")
    private String rawGatewayPayload;

    // Gateway round-trip time - feeds SLA/performance metrics later.
    @Column(updatable = false)
    private Long durationMs;

    @Column(nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    public void prePersist() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}
