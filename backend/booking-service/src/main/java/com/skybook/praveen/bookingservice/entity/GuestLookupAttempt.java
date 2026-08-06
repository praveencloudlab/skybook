package com.skybook.praveen.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One FAILED guest-lookup attempt (GUEST_CHECKIN_MODULE.md §6). The table is
 * the distributed-guessing brake: failures are counted per booking reference
 * in a sliding window with a query, which is correct at any instance count
 * because the database is the shared state.
 */
@Entity
@Table(name = "guest_lookup_attempts")
@Getter
@Setter
public class GuestLookupAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_reference", nullable = false, length = 20)
    private String bookingReference;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();
}
