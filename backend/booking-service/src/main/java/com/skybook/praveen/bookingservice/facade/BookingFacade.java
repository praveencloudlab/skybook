package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryServiceClient;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestration layer (docs sections 2 and 8) - the only place that knows
 * other services/concerns exist: flight validation via FlightServiceClient,
 * seat control via InventoryServiceClient (Sprint 6), persistence via
 * BookingService, notifications via BookingEventProducer.
 *
 * Sprint 6 flow:
 * - create: validate flight -> create booking -> HOLD each passenger seat
 *   (skipped when the flight has no inventory record; any conflict releases
 *   already-taken holds, cancels the booking, and surfaces a 409)
 * - confirmFromPayment: driven by PaymentEventConsumer on PAYMENT_SUCCEEDED -
 *   confirms the booking with the real payment reference, converts holds to
 *   reservations, publishes CONFIRMED
 * - manual confirm: kept as a back-office override (simulated payment)
 * - cancel: releases holds/reservations quietly; payment-service refunds by
 *   consuming the CANCELLED event
 *
 * Deliberately NOT @Transactional: BookingService's individual methods are.
 * By the time a method here calls bookingService.xxx(...) and gets a
 * response back, that call's transaction has already committed - so
 * publishing to Kafka afterwards is equivalent to
 * @TransactionalEventListener(phase = AFTER_COMMIT) without the extra
 * indirection (revisit with a transactional outbox if stronger delivery
 * guarantees are ever needed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingFacade {

    private final FlightServiceClient flightServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final BookingService bookingService;
    private final BookingEventProducer bookingEventProducer;

    public BookingResponse createBooking(CreateBookingRequest request) {

        FlightDetails flight = flightServiceClient.getFlight(request.flightId());

        if (flight.status() == FlightBookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot book a cancelled flight");
        }

        BookingResponse booking = bookingService.createBooking(request, flight.departureTime());

        holdSeatsOrCompensate(booking);

        bookingEventProducer.publishBookingCreated(booking);

        return booking;
    }

    /** Back-office override - the normal path is confirmBookingFromPayment via PaymentEventConsumer. */
    public BookingResponse confirmBooking(Long id) {

        BookingResponse booking = bookingService.confirmBooking(id);

        reserveHeldSeatsQuietly(booking);

        bookingEventProducer.publishBookingConfirmed(booking);

        return booking;
    }

    /**
     * The Sprint 6 event-driven path: PAYMENT_SUCCEEDED arrived. Idempotent -
     * a redelivered event finds the booking already CONFIRMED and only
     * re-runs the quiet reservation conversion (itself idempotent-safe).
     */
    public BookingResponse confirmBookingFromPayment(Long bookingId, String paymentReference) {

        BookingService.PaymentConfirmation confirmation =
                bookingService.confirmBookingFromPayment(bookingId, paymentReference);

        BookingResponse booking = confirmation.booking();

        reserveHeldSeatsQuietly(booking);

        if (confirmation.transitioned()) {
            bookingEventProducer.publishBookingConfirmed(booking);
        }

        return booking;
    }

    public BookingResponse cancelBooking(Long id, String reason) {

        BookingResponse booking = bookingService.cancelBooking(id, reason);

        // Return the seats to the pool - holds if never confirmed,
        // reservations if it was. Cleanup must not fail the cancellation.
        for (BookingPassengerResponse passenger : booking.passengers()) {
            if (passenger.seatNumber() != null && !passenger.seatNumber().isBlank()) {
                inventoryServiceClient.releaseHoldQuietly(booking.flightId(),
                        passenger.seatNumber(), booking.id(), "booking cancelled");
                inventoryServiceClient.cancelReservationQuietly(booking.flightId(),
                        passenger.seatNumber(), booking.id(), "booking cancelled");
            }
        }

        bookingEventProducer.publishBookingCancelled(booking);

        return booking;
    }

    // ---------------------------------------------------------------
    // Seat-inventory internals
    // ---------------------------------------------------------------

    /**
     * Hold every passenger's seat. First "no inventory for this flight"
     * answer short-circuits the rest (hold-if-exists policy). Any conflict
     * compensates: releases the holds already taken, cancels the
     * just-created booking, rethrows.
     */
    private void holdSeatsOrCompensate(BookingResponse booking) {

        List<String> heldSeats = new ArrayList<>();
        try {
            for (BookingPassengerResponse passenger : booking.passengers()) {
                String seat = passenger.seatNumber();
                if (seat == null || seat.isBlank()) {
                    continue;
                }
                var hold = inventoryServiceClient.holdSeat(booking.flightId(), seat, booking.id());
                if (hold.isEmpty()) {
                    return; // flight has no inventory - nothing to hold
                }
                heldSeats.add(seat);
            }
        } catch (RuntimeException holdFailure) {
            for (String seat : heldSeats) {
                inventoryServiceClient.releaseHoldQuietly(booking.flightId(), seat, booking.id(),
                        "compensation - another seat in this booking was unavailable");
            }
            bookingService.cancelBooking(booking.id(),
                    "Seat hold failed: " + holdFailure.getMessage());
            log.warn("Booking {} rolled back - {}", booking.bookingReference(), holdFailure.getMessage());
            throw holdFailure;
        }
    }

    /**
     * Convert the booking's holds into reservations after confirmation.
     * Quiet: payment has already been taken - a reservation hiccup must not
     * fail the confirmation; inventory's ledger + logs surface it.
     */
    private void reserveHeldSeatsQuietly(BookingResponse booking) {

        for (BookingPassengerResponse passenger : booking.passengers()) {
            String seat = passenger.seatNumber();
            if (seat == null || seat.isBlank()) {
                continue;
            }
            try {
                inventoryServiceClient.reserveSeat(booking.flightId(), seat, booking.id(), passenger.id());
            } catch (RuntimeException e) {
                log.warn("Could not convert hold to reservation for seat {} on booking {}: {}",
                        seat, booking.bookingReference(), e.getMessage());
            }
        }
    }
}
