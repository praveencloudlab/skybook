package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryHoldDetails;
import com.skybook.praveen.bookingservice.client.InventoryServiceClient;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.SeatAssignmentResult;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.PassengerBookingDetail;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.QuoteResponse;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestration layer (docs sections 2 and 8) - the only place that knows
 * other services/concerns exist: flight validation via FlightServiceClient,
 * seat control via InventoryServiceClient, persistence via BookingService,
 * notifications via BookingEventProducer.
 *
 * Seat-selection flow (SEAT_SELECTION_MODULE.md §5.1, draft -> hold -> finalize):
 * - create: validate flight -> createDraftBooking (DRAFT, seat NULL, no
 *   payment; tx commits so booking/passenger IDs exist) -> per-passenger
 *   inventory hold OUTSIDE any booking tx (blank seat => atomic AUTO pick,
 *   non-blank => MANUAL with cabin validation) -> finalizeSeatAssignments
 *   (one tx: seats + surcharges + totals + BookingPayment + DRAFT->CREATED)
 *   -> publish CREATED with the final totals. Any failure releases the holds
 *   already taken, cancels the draft, rethrows.
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

    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");
    private static final String QUOTE_CURRENCY = "USD";

    private final FlightServiceClient flightServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final BookingService bookingService;
    private final BookingEventProducer bookingEventProducer;
    private final FareCalculator fareCalculator;

    public BookingResponse createBooking(CreateBookingRequest request) {

        FlightDetails flight = flightServiceClient.getFlight(request.flightId());

        if (flight.status() == FlightBookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot book a cancelled flight");
        }

        BookingResponse draft = bookingService.createDraftBooking(request, flight.departureTime());

        List<SeatAssignmentResult> assignments = holdSeatsOrCompensate(draft, request);

        BookingResponse booking = finalizeOrCompensate(draft, assignments);

        // Only a finalized (DRAFT -> CREATED) booking is announced (§5.1a).
        bookingEventProducer.publishBookingCreated(booking, flight);

        return booking;
    }

    /** Back-office override - the normal path is confirmBookingFromPayment via PaymentEventConsumer. */
    public BookingResponse confirmBooking(Long id) {

        BookingResponse booking = bookingService.confirmBooking(id);

        reserveHeldSeatsQuietly(booking);

        bookingEventProducer.publishBookingConfirmed(booking, flightOrNull(booking.flightId()));

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
            bookingEventProducer.publishBookingConfirmed(booking, flightOrNull(booking.flightId()));
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

        bookingEventProducer.publishBookingCancelled(booking, flightOrNull(booking.flightId()));

        return booking;
    }

    /**
     * Fare options for a flight (§11): the ONLY place inventory's cabin
     * availability and FareCalculator's base fares are combined - neither
     * service ever computes the other's numbers. Cabins the aircraft doesn't
     * have simply aren't quoted (§7); a flight without any seat inventory
     * quotes every cabin with unknown (null) availability.
     */
    public QuoteResponse quoteFares(Long flightId) {

        FlightDetails flight = flightServiceClient.getFlight(flightId);

        if (flight.status() == FlightBookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot quote a cancelled flight");
        }

        List<QuoteResponse.CabinQuote> cabins = inventoryServiceClient.getCabins(flightId)
                .map(available -> available.stream()
                        .map(cabin -> cabinQuote(cabin.travelClass(), cabin.availableSeats()))
                        .toList())
                .orElseGet(() -> Arrays.stream(TravelClass.values())
                        .map(travelClass -> cabinQuote(travelClass, null))
                        .toList());

        return new QuoteResponse(flightId, QUOTE_CURRENCY, cabins);
    }

    private QuoteResponse.CabinQuote cabinQuote(TravelClass travelClass, Integer availableSeats) {
        Map<FareType, BigDecimal> baseFares = new EnumMap<>(FareType.class);
        for (FareType fareType : FareType.values()) {
            baseFares.put(fareType, fareCalculator.calculateFare(travelClass, fareType));
        }
        BigDecimal fromFare = baseFares.values().stream().min(BigDecimal::compareTo).orElseThrow();
        return new QuoteResponse.CabinQuote(travelClass, availableSeats, baseFares, fromFare);
    }

    /**
     * Flight context for email enrichment - best-effort by design: an email
     * without route details beats a confirmation that fails because
     * flight-service was briefly down.
     */
    private FlightDetails flightOrNull(Long flightId) {
        try {
            return flightServiceClient.getFlight(flightId);
        } catch (RuntimeException e) {
            log.warn("Could not fetch flight {} for event enrichment: {}", flightId, e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Seat-inventory internals
    // ---------------------------------------------------------------

    /**
     * Stage 2 of draft -> hold -> finalize (§5.1): one inventory hold per
     * passenger, outside any booking transaction. The facade decides the mode
     * directly - blank requested seat => atomic AUTO pick, non-blank => MANUAL
     * (round 5: the String strategy contract is deleted, inventory's hold
     * response is the authoritative resolution AND the pricing authority).
     *
     * Draft passengers carry no seat, so each is correlated with its request
     * detail by position - createDraftBooking persists them in request order.
     *
     * First "no inventory for this flight" answer short-circuits the rest
     * (pre-existing hold-if-exists policy): manual passengers keep their
     * requested seat unpriced, auto passengers stay seatless - both charged 0,
     * because without inventory there is no pricing authority to consult.
     *
     * Any failure compensates: releases the holds already taken, cancels the
     * draft (DRAFT -> CANCELLED), rethrows.
     */
    private List<SeatAssignmentResult> holdSeatsOrCompensate(BookingResponse draft,
                                                             CreateBookingRequest request) {

        List<SeatAssignmentResult> assignments = new ArrayList<>();
        List<String> heldSeats = new ArrayList<>();
        try {
            for (int i = 0; i < draft.passengers().size(); i++) {

                BookingPassengerResponse passenger = draft.passengers().get(i);
                PassengerBookingDetail detail = request.passengers().get(i);
                String requestedSeat = detail.seatNumber();
                boolean manual = requestedSeat != null && !requestedSeat.isBlank();

                Optional<InventoryHoldDetails> hold = manual
                        ? inventoryServiceClient.holdSeat(draft.flightId(),
                                requestedSeat.toUpperCase(), draft.id(), passenger.id(), detail.travelClass())
                        : inventoryServiceClient.autoHoldSeat(draft.flightId(),
                                draft.id(), passenger.id(), detail.travelClass());

                if (hold.isEmpty()) {
                    // No inventory record for this flight: nothing is held or
                    // priced for ANY passenger (the policy is per-flight).
                    return noInventoryAssignments(draft, request);
                }

                InventoryHoldDetails held = hold.get();
                assignments.add(new SeatAssignmentResult(
                        passenger.id(),
                        held.seatNumber(),
                        held.listedSurcharge(),
                        held.chargedSurcharge(),
                        SeatAssignmentMode.valueOf(held.assignmentMode())));
                heldSeats.add(held.seatNumber());
            }
            return assignments;

        } catch (RuntimeException holdFailure) {
            compensate(draft, heldSeats, "Seat hold failed: " + holdFailure.getMessage());
            throw holdFailure;
        }
    }

    /** Stage 3 (§5.1): one tx for all money fields + payment + DRAFT->CREATED; compensates on failure. */
    private BookingResponse finalizeOrCompensate(BookingResponse draft, List<SeatAssignmentResult> assignments) {
        try {
            return bookingService.finalizeSeatAssignments(draft.id(), assignments);
        } catch (RuntimeException finalizeFailure) {
            List<String> heldSeats = assignments.stream()
                    .map(SeatAssignmentResult::seatNumber)
                    .filter(seat -> seat != null && !seat.isBlank())
                    .toList();
            compensate(draft, heldSeats, "Finalization failed: " + finalizeFailure.getMessage());
            throw finalizeFailure;
        }
    }

    /** Hold-if-exists fallback: seats as requested (manual) or none (auto), all charged 0. */
    private List<SeatAssignmentResult> noInventoryAssignments(BookingResponse draft,
                                                              CreateBookingRequest request) {
        List<SeatAssignmentResult> assignments = new ArrayList<>();
        for (int i = 0; i < draft.passengers().size(); i++) {
            String requestedSeat = request.passengers().get(i).seatNumber();
            boolean manual = requestedSeat != null && !requestedSeat.isBlank();
            assignments.add(new SeatAssignmentResult(
                    draft.passengers().get(i).id(),
                    manual ? requestedSeat.toUpperCase() : null,
                    ZERO_MONEY, ZERO_MONEY,
                    manual ? SeatAssignmentMode.MANUAL : SeatAssignmentMode.AUTO));
        }
        return assignments;
    }

    /** Release taken holds and cancel the draft (DRAFT -> CANCELLED, §5.1a). */
    private void compensate(BookingResponse draft, List<String> heldSeats, String reason) {
        for (String seat : heldSeats) {
            inventoryServiceClient.releaseHoldQuietly(draft.flightId(), seat, draft.id(),
                    "compensation - " + reason);
        }
        bookingService.cancelBooking(draft.id(), reason);
        log.warn("Draft booking {} rolled back - {}", draft.bookingReference(), reason);
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
