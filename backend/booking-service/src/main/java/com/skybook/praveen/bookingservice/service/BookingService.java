package com.skybook.praveen.bookingservice.service;

import com.skybook.praveen.bookingservice.domain.SeatAssignmentResult;
import com.skybook.praveen.bookingservice.dto.request.BookingSearchRequest;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns the Booking aggregate only (docs section 2) - CRUD and status
 * transitions on Booking/BookingPassenger/BookingContact/BookingPayment.
 * Deliberately knows nothing about Flight, Payment, or any other service;
 * that orchestration is BookingFacade's job.
 */
public interface BookingService {

    /**
     * Stage 1 of draft -> hold -> finalize (SEAT_SELECTION_MODULE.md §5.1):
     * commits the booking as DRAFT with seat_number NULL, fare = base fare
     * only, and NO BookingPayment row - the facade needs the committed
     * booking/passenger IDs before it can take inventory holds.
     *
     * @param journey      the journey's legs in segment order, one per flight,
     *                      built by BookingFacade AFTER validating each flight
     *                      with flight-service - this service needs departure
     *                      times for pricing/passport/bookability checks but
     *                      must not fetch flights itself. A one-way is one leg;
     *                      a round trip adds a direction-1 leg; a same-carrier
     *                      through-ticket adds direction-0 connection legs.
     * @param ownerSubject the authenticated JWT subject captured as the
     *                     booking owner (SECURITY_HARDENING_MODULE.md §4.2).
     */
    BookingResponse createDraftBooking(CreateBookingRequest request, List<JourneyLeg> journey,
                                       String ownerSubject);

    /**
     * One leg of the journey being booked (ROUND_TRIP_MODULE.md §3 + the
     * through-ticketing extension). directionStart marks the first leg of a
     * direction: baggage fees charge once per DIRECTION, never per leg - a
     * through-ticket checks bags through its connection.
     */
    record JourneyLeg(Long flightId, LocalDateTime departureTime, int direction, boolean directionStart) {
    }

    /**
     * Stage 3 (§5.1): ONE transaction that synchronizes all money fields from
     * the hold results - per-passenger seat/surcharge/mode, fare = base +
     * charged, Booking.totalFare - creates BookingPayment(PENDING, finalTotal)
     * and promotes DRAFT -> CREATED. Invariant at return:
     * sum(passenger.fare) = totalFare = payment.amount.
     */
    BookingResponse finalizeSeatAssignments(Long bookingId, List<SeatAssignmentResult> assignments);

    /** Cancels DRAFT bookings older than the configured TTL (stale-draft sweep, §5.1a). Returns how many. */
    int cancelStaleDrafts();

    /**
     * Cancel one whole segment - "drop the return" (ROUND_TRIP_MODULE.md §7).
     * Only segmentIndex >= 1 may be cancelled alone: dropping the outbound
     * while flying the return is the no-show pattern airlines void tickets
     * over. All the segment's active rows cancel, their coupons go REFUNDED,
     * and the booking derives PARTIALLY_CANCELLED (or CANCELLED if nothing
     * remains).
     */
    CancelPassengersResponse cancelSegment(Long bookingId, int segmentIndex);

    /**
     * Premium date change (ROUND_TRIP_MODULE.md §11): move ONE segment to a
     * new flight, keeping the same booking and tickets. Old rows cancel with
     * coupons CANCELLED (exchanged, not refunded); replacement rows are
     * created seatless on the new flight priced at ITS departure; each
     * ticket gains a fresh OPEN coupon; totalFare and the payment snapshot
     * adjust by the fare difference (simulated processor). Guarded to
     * PREMIUM rows - other fare families change dates via cancel + rebook.
     */
    BookingResponse rebookSegment(Long bookingId, int segmentIndex, Long newFlightId,
                                  LocalDateTime newDepartureTime);

    /** Write the seats the facade holds+reserves for freshly rebooked rows (seat only - Premium seat picks are free). */
    BookingResponse applySeatNumbers(Long bookingId, java.util.Map<Long, String> seatByRowId);

    BookingResponse getBookingById(Long id);

    BookingResponse getBookingByReference(String bookingReference);

    List<BookingResponse> getAllBookings();

    /** Bookings owned by one authenticated subject, newest first (§4.2). */
    List<BookingResponse> getBookingsForOwner(String ownerSubject);

    List<BookingResponse> searchBookings(BookingSearchRequest criteria);

    /** Back-office override - simulates payment success directly. The normal
     *  Sprint 6 path is confirmBookingFromPayment, driven by PAYMENT_SUCCEEDED. */
    BookingResponse confirmBooking(Long id);

    /** transitioned = false when the booking was already CONFIRMED (idempotent event replay). */
    record PaymentConfirmation(BookingResponse booking, boolean transitioned) {
    }

    /** Event-driven confirmation: records the real payment reference from payment-service. */
    PaymentConfirmation confirmBookingFromPayment(Long bookingId, String paymentReference);

    BookingResponse cancelBooking(Long id, String reason);

    /**
     * Cancel specific passengers off a booking. The booking survives with its
     * remaining passengers; only when the last one is cancelled does the booking
     * itself become CANCELLED. Enforces the guardian rule (a minor cannot remain
     * without an adult). Returns the updated booking, the refund calculated for
     * the cancelled passengers, and whether the booking was emptied.
     */
    CancelPassengersResponse cancelPassengers(Long bookingId, java.util.List<Long> bookingPassengerIds);

    BookingResponse completeBooking(Long id);

    /** bookingPassengerId identifies the passenger's line item within this booking (not Passenger.id). */
    BookingResponse checkInPassenger(Long bookingId, Long bookingPassengerId);

    BookingResponse boardPassenger(Long bookingId, Long bookingPassengerId);

    /**
     * Mirror checkin-service's authoritative per-passenger state onto the
     * {@code BookingPassenger.checkInStatus} read-model (consumed from
     * CheckInEvent). This is what arms the "a checked-in passenger cannot be
     * cancelled online" guard in {@link #cancelPassengers} - without it the
     * read-model stays NOT_OPEN forever and the guard never fires.
     */
    void applyCheckInStatus(Long bookingId, Long bookingPassengerId,
                            com.skybook.praveen.bookingservice.enums.CheckInStatus target);
}
