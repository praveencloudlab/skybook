package com.skybook.praveen.checkinservice.entity;

import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;
import com.skybook.praveen.common.entity.Auditable;
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
 * Append-only audit trail (design doc section 3.4) - same mechanics and
 * provenance columns as PaymentHistory/BookingHistory: written in-memory by
 * CheckInStateMachine, persisted via CheckIn's cascade.
 *
 * Vocabulary: actor = USER | SYSTEM | KAFKA | SCHEDULER;
 * source = API | BOOKING_EVENT | NO_SHOW_JOB | MANIFEST_JOB.
 */
@Entity
@Table(name = "check_in_history", indexes = {
        @Index(name = "ix_check_in_history_checkin", columnList = "check_in_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckInHistory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_in_id", nullable = false, updatable = false)
    private CheckIn checkIn;

    @Enumerated(EnumType.STRING)
    @Column(name = "history_type", nullable = false, updatable = false, length = 25)
    private CheckInHistoryType historyType;

    // Who acted: USER | SYSTEM | KAFKA | SCHEDULER.
    @Column(nullable = false, updatable = false, length = 20)
    private String actor;

    // Where the change came from: API | BOOKING_EVENT | NO_SHOW_JOB | MANIFEST_JOB.
    @Column(nullable = false, updatable = false, length = 30)
    private String source;

    // Ties the change to its trigger: request id, bookingReference for
    // event-driven changes, job run id.
    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(updatable = false, length = 500)
    private String details;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    public void prePersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
