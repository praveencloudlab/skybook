package com.skybook.praveen.bookingservice.service;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.GuestLookupAttempt;
import com.skybook.praveen.bookingservice.exception.GuestLookupFailedException;
import com.skybook.praveen.bookingservice.exception.GuestLookupThrottledException;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import com.skybook.praveen.bookingservice.repository.GuestLookupAttemptRepository;
import com.skybook.praveen.bookingservice.security.GuestTokenFetcher;
import com.skybook.praveen.bookingservice.security.SurnameNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Guest-session issuance (GUEST_CHECKIN_MODULE.md §3/§6): verify reference +
 * surname against the data, brake distributed guessing, and exchange the
 * verified pair for a booking-scoped guest token minted by auth-service.
 *
 * <p>Every mismatch arm - unknown reference, wrong name, name of a CANCELLED
 * passenger, fully cancelled booking - answers the same generic 404 through
 * the same code path, so neither wording nor timing tells a prober which part
 * was wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestSessionService {

    static final String GENERIC_MISMATCH = "We couldn't find a booking matching those details.";
    private static final int MAX_FAILURES_PER_WINDOW = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final BookingRepository bookingRepository;
    private final GuestLookupAttemptRepository attemptRepository;
    private final GuestTokenFetcher guestTokenFetcher;

    public record GuestSession(long bookingId, String token) {
    }

    @Transactional
    public GuestSession issue(String rawReference, String rawLastName) {
        String reference = rawReference == null ? "" : rawReference.trim().toUpperCase(Locale.ROOT);

        // The brake comes FIRST, correct credentials or not: five failures in
        // the window lock this reference's lookup, so a distributed guess
        // against one booking dies even when attempt six would have hit.
        Instant windowStart = Instant.now().minus(WINDOW);
        if (attemptRepository.countByBookingReferenceAndAttemptedAtAfter(reference, windowStart)
                >= MAX_FAILURES_PER_WINDOW) {
            log.warn("Guest lookup throttled for reference {}**", mask(reference));
            throw new GuestLookupThrottledException();
        }

        Booking booking = bookingRepository.findByBookingReference(reference).orElse(null);
        // The name lives on the Passenger aggregate, one hop from the booking
        // row (LAZY, resolved inside this @Transactional). Only ACTIVE rows
        // count: a cancelled-out passenger's surname must not unlock the
        // booking they were removed from (§6, tested).
        boolean matched = booking != null
                && !"CANCELLED".equalsIgnoreCase(String.valueOf(booking.getBookingStatus()))
                && booking.getPassengers().stream()
                        .filter(p -> !p.isCancelled())
                        .anyMatch(p -> p.getPassenger() != null
                                && SurnameNormalizer.matches(p.getPassenger().getLastName(), rawLastName));

        if (!matched) {
            GuestLookupAttempt failure = new GuestLookupAttempt();
            failure.setBookingReference(reference);
            attemptRepository.save(failure);
            // Opportunistic pruning keeps the table tiny without a scheduler.
            attemptRepository.deleteOlderThan(Instant.now().minus(WINDOW.multipliedBy(4)));
            throw new GuestLookupFailedException();
        }

        String token = guestTokenFetcher.fetch(booking.getId());
        log.info("Guest session issued for reference {}** (booking {})", mask(reference), booking.getId());
        return new GuestSession(booking.getId(), token);
    }

    /** First two characters only - enough to correlate, useless to redeem. */
    private static String mask(String reference) {
        return reference.length() <= 2 ? reference : reference.substring(0, 2);
    }
}
