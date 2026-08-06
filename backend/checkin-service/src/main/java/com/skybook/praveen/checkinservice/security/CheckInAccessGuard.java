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

    /**
     * Owner, admin, or guest-of-this-booking for a single check-in
     * (GUEST_CHECKIN_MODULE.md §3.3). 404 if it doesn't exist - and 404, not
     * 403, when a guest reaches outside its one booking (decision D8).
     */
    public void requireOwnerOfCheckIn(Long checkInId) {
        CheckIn checkIn = checkInRepository.findById(checkInId)
                .orElseThrow(() -> CheckInNotFoundException.byId(checkInId));
        SecurityAccess.requireBookingAccess(checkIn.getOwnerSubject(), checkIn.getBookingId());
    }

    /**
     * Booking-level access to a booking's check-ins. Every check-in on a
     * booking shares the booking's owner, so the first row decides.
     *
     * <p>The empty case no longer skips the check wholesale (the §2.8 review
     * finding): a GUEST is judged against its token's booking scope even when
     * no rows exist yet - outside that scope, an empty answer would still be
     * an answer. For USER/ADMIN an empty result stays allowed through, as
     * before: the controller returns an empty list and there is nothing to
     * leak, while refusing would break owners viewing a booking whose
     * check-ins haven't been created yet.
     */
    public void requireOwnerOfBooking(Long bookingId) {
        List<CheckIn> checkIns = checkInRepository.findByBookingId(bookingId);
        if (!checkIns.isEmpty()) {
            SecurityAccess.requireBookingAccess(checkIns.get(0).getOwnerSubject(), bookingId);
            return;
        }
        if (SecurityAccess.isGuest()) {
            SecurityAccess.requireBookingAccess(null, bookingId);
        }
    }
}
