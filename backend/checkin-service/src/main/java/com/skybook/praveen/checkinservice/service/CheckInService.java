package com.skybook.praveen.checkinservice.service;

import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns the CheckIn aggregate only (design doc section 3.1) - CRUD and
 * status transitions on CheckIn/CheckInHistory. Pure DB layer, no I/O to
 * other services - that orchestration (seat-reserved checks, flight-status
 * checks, seat-change calls) is CheckInFacade's job, same split as
 * booking-service's BookingService/BookingFacade.
 */
public interface CheckInService {

    /**
     * Idempotent by bookingPassengerId - a duplicate create is a no-op
     * returning the existing row. actor/source/correlationId are threaded
     * through explicitly since this one method serves two callers with
     * different provenance: the manual POST endpoint (USER/API) and the
     * BookingEvent CONFIRMED consumer (KAFKA/BOOKING_EVENT).
     */
    CheckInResponse createCheckIn(CreateCheckInRequest request, String actor, String source, String correlationId);

    CheckInResponse getById(Long id);

    List<CheckInResponse> getByBookingId(Long bookingId);

    List<CheckInResponse> getByFlightId(Long flightId);

    /** Idempotent - a no-op if already OPEN. */
    CheckInResponse openWindow(Long id);

    /**
     * Implicitly opens the window first if still NOT_OPEN (design doc
     * section 7 - same pattern booking-service's stopgap checkInPassenger
     * already used). Validates window timing and document presence
     * (pure - no I/O); seat-reserved/flight-cancelled checks happen in the
     * facade before this is called.
     */
    CheckInResponse recordCheckIn(Long id);

    /** Validates the boarding window (pure); flight-cancelled check happens in the facade. */
    CheckInResponse recordBoarding(Long id);

    CheckInResponse changeSeatNumber(Long id, String newSeatNumber);

    CheckInResponse assignGate(Long id, String gate);

    /** BookingEvent CANCELLED cascade - transitions every non-terminal CheckIn for the booking. */
    void cancelAllForBooking(Long bookingId, String reason);

    /**
     * No-show sweep (design doc section 5.7/10) - sweeps every SWEEPABLE
     * row whose departureTime is before departureCutoff. The caller (the
     * scheduler) computes departureCutoff as now + gate-close offset, since
     * "gate closed" for a given row means departureTime - offset &lt; now,
     * i.e. departureTime &lt; now + offset. Returns the number of rows swept.
     */
    int sweepNoShows(LocalDateTime departureCutoff);
}
