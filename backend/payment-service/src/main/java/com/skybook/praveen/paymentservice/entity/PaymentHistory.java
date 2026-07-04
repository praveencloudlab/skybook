package com.skybook.praveen.paymentservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
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

import java.time.LocalDateTime;

/**
 * Append-only audit trail (same mechanics as Booking/Inventory history:
 * written in-memory by the state machine, persisted via Payment's cascade),
 * with provenance columns for production debugging (design doc section 3.5):
 * actor and source are string vocabularies, deliberately not enums - a new
 * actor must not require a code change.
 *
 * Vocabulary: actor = USER | SYSTEM | KAFKA | SCHEDULER;
 * source = API | BOOKING_EVENT | EXPIRY_JOB.
 */
@Entity
@Table(name = "payment_history", indexes = {
        @Index(name = "ix_payment_history_payment", columnList = "payment_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentHistory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 25)
    private PaymentHistoryType historyType;

    // Who acted: USER | SYSTEM | KAFKA | SCHEDULER.
    @Column(nullable = false, updatable = false, length = 20)
    private String actor;

    // Where the change came from: API | BOOKING_EVENT | EXPIRY_JOB.
    @Column(nullable = false, updatable = false, length = 30)
    private String source;

    // Ties the change to its trigger: request id, bookingReference for
    // event-driven changes, job run id.
    @Column(updatable = false, length = 64)
    private String correlationId;

    @Column(updatable = false, length = 500)
    private String details;

    @Column(nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    public void prePersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
