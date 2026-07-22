package com.skybook.praveen.checkinservice.security;

import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.exception.CheckInNotFoundException;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import com.skybook.praveen.security.SecurityAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Object-level ownership enforcement for check-in HTTP endpoints
 * (SECURITY_HARDENING_MODULE.md §4.2). Called at the CONTROLLER boundary only -
 * the event-driven manifest/check-in flows invoke the service directly on a
 * Kafka thread with no SecurityContext, and must never hit these checks. A USER
 * may act only on check-ins for their own booking; ADMIN/SERVICE may act on any;
 * a legacy null-owner row is privileged-only.
 */
@Component
@RequiredArgsConstructor
public class CheckInAccessGuard {

    private final CheckInRepository checkInRepository;

    /** Owner-or-admin of a single check-in. 404 if it doesn't exist. */
    public void requireOwnerOfCheckIn(Long checkInId) {
        CheckIn checkIn = checkInRepository.findById(checkInId)
                .orElseThrow(() -> CheckInNotFoundException.byId(checkInId));
        SecurityAccess.requireOwnerOrAdmin(checkIn.getOwnerSubject());
    }

    /**
     * Owner-or-admin of a booking's check-ins. Every check-in on a booking
     * shares the booking's owner, so the first row decides. An empty result is
     * allowed through (the controller returns an empty list - nothing to leak).
     */
    public void requireOwnerOfBooking(Long bookingId) {
        List<CheckIn> checkIns = checkInRepository.findByBookingId(bookingId);
        if (!checkIns.isEmpty()) {
            SecurityAccess.requireOwnerOrAdmin(checkIns.get(0).getOwnerSubject());
        }
    }
}
