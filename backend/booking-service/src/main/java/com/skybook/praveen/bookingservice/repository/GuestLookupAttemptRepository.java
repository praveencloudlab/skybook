package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.GuestLookupAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface GuestLookupAttemptRepository extends JpaRepository<GuestLookupAttempt, Long> {

    long countByBookingReferenceAndAttemptedAtAfter(String bookingReference, Instant windowStart);

    /** Opportunistic pruning - rows outside every window are dead weight. */
    @Modifying
    @Query("DELETE FROM GuestLookupAttempt a WHERE a.attemptedAt < :cutoff")
    void deleteOlderThan(Instant cutoff);
}
