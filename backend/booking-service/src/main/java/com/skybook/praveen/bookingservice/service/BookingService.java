package com.skybook.praveen.bookingservice.service;

import com.skybook.praveen.bookingservice.domain.SeatAssignmentResult;
import com.skybook.praveen.bookingservice.dto.request.BookingSearchRequest;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;

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
     * @param flightDepartureTime supplied by the caller (BookingFacade, after
     *                            validating the flight with flight-service) -
     *                            this service needs it for passport-validity
     *                            checks but must not fetch it itself.
     */
    BookingResponse createDraftBooking(CreateBookingRequest request, LocalDateTime flightDepartureTime);

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

    BookingResponse getBookingById(Long id);

    BookingResponse getBookingByReference(String bookingReference);

    List<BookingResponse> getAllBookings();

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

    BookingResponse completeBooking(Long id);

    /** bookingPassengerId identifies the passenger's line item within this booking (not Passenger.id). */
    BookingResponse checkInPassenger(Long bookingId, Long bookingPassengerId);

    BookingResponse boardPassenger(Long bookingId, Long bookingPassengerId);
}
