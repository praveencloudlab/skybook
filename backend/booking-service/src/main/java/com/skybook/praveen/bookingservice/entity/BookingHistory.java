package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.BookingHistoryField;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * Append-only audit trail of every status transition across the three
 * independent state machines (docs section 3.5). Deliberately does NOT
 * extend Auditable - this row IS the audit record, so its own
 * created/updated/version bookkeeping would be redundant; changedAt/changedBy
 * already capture who did what, when.
 *
 * Populated by BookingStateMachine, which appends to Booking.history
 * in-memory - persistence happens for free via Booking's cascade, so nothing
 * else needs to remember to write here explicitly.
 */
@Entity
@Table(name = "booking_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false, length = 20)
    private BookingHistoryField fieldChanged;

    @Column(nullable = false)
    private String fromValue;

    @Column(nullable = false)
    private String toValue;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    private String changedBy;

    private String reason;

    @PrePersist
    public void prePersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
