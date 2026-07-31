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
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;
import com.skybook.praveen.bookingservice.dto.response.FareAlertResponse;
import com.skybook.praveen.bookingservice.dto.response.FareCalendarDayResponse;
import com.skybook.praveen.bookingservice.entity.FareAlert;
import com.skybook.praveen.bookingservice.repository.FareAlertRepository;
import com.skybook.praveen.bookingservice.dto.response.QuoteResponse;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private static final String QUOTE_CURRENCY = "GBP";

    private final FlightServiceClient flightServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final BookingService bookingService;
    private final BookingEventProducer bookingEventProducer;
    private final FareCalculator fareCalculator;
    private final FareAlertRepository fareAlertRepository;

    public BookingResponse createBooking(CreateBookingRequest request) {

        FlightDetails flight = flightServiceClient.getFlight(request.flightId());

        if (flight.status() == FlightBookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot book a cancelled flight");
        }

        // Through-ticket connection legs (same-carrier, one direction): every
        // leg validated up front - bookable and chronological after the
        // previous leg's arrival - before any row or hold exists.
        List<FlightDetails> outboundLegs = new ArrayList<>(List.of(flight));
        if (request.connectionFlightIds() != null && !request.connectionFlightIds().isEmpty()) {
            for (Long legId : request.connectionFlightIds()) {
                FlightDetails legFlight = flightServiceClient.getFlight(legId);
                if (legFlight.status() == FlightBookingStatus.CANCELLED) {
                    throw new IllegalArgumentException("Cannot book a cancelled connection flight");
                }
                FlightDetails previous = outboundLegs.get(outboundLegs.size() - 1);
                if (previous.arrivalTime() != null
                        && !legFlight.departureTime().isAfter(previous.arrivalTime())) {
                    throw new IllegalArgumentException(
                            "Connection flight " + legId + " departs before the previous leg arrives");
                }
                outboundLegs.add(legFlight);
            }
        }

        // Round trip (ROUND_TRIP_MODULE.md §5): validate the return leg up
        // front - both flights bookable and the chronology sane - before any
        // row or hold exists, so a bad pair fails cheap.
        FlightDetails returnFlight = null;
        if (request.returnFlightId() != null) {
            returnFlight = flightServiceClient.getFlight(request.returnFlightId());
            if (returnFlight.status() == FlightBookingStatus.CANCELLED) {
                throw new IllegalArgumentException("Cannot book a cancelled return flight");
            }
            // Chronology against the LAST outbound leg: a through-ticketed
            // outbound arrives when its final connection lands.
            FlightDetails lastOutbound = outboundLegs.get(outboundLegs.size() - 1);
            if (lastOutbound.arrivalTime() != null
                    && !returnFlight.departureTime().isAfter(lastOutbound.arrivalTime())) {
                throw new IllegalArgumentException(
                        "The return flight must depart after the outbound arrives");
            }
        }

        // The journey in segment order: direction-0 legs (outbound + any
        // through-connection), then the direction-1 return. Bags charge once
        // per direction, so only each direction's first leg is a start.
        List<BookingService.JourneyLeg> journey = new ArrayList<>();
        for (int i = 0; i < outboundLegs.size(); i++) {
            journey.add(new BookingService.JourneyLeg(
                    outboundLegs.get(i).id(), outboundLegs.get(i).departureTime(), 0, i == 0));
        }
        if (returnFlight != null) {
            journey.add(new BookingService.JourneyLeg(
                    returnFlight.id(), returnFlight.departureTime(), 1, true));
        }

        BookingResponse draft = bookingService.createDraftBooking(request, journey, currentSubject());

        List<SeatAssignmentResult> assignments = holdSeatsOrCompensate(draft, request);

        BookingResponse booking = finalizeOrCompensate(draft, assignments);

        // Only a finalized (DRAFT -> CREATED) booking is announced (§5.1a).
        // Every leg's flight details ride the event (already fetched above).
        List<FlightDetails> journeyFlights = new ArrayList<>(outboundLegs);
        if (returnFlight != null) {
            journeyFlights.add(returnFlight);
        }
        bookingEventProducer.publishBookingCreated(booking, journeyFlights);

        return booking;
    }

    /** Back-office override - the normal path is confirmBookingFromPayment via PaymentEventConsumer. */
    public BookingResponse confirmBooking(Long id) {

        BookingResponse booking = bookingService.confirmBooking(id);

        reserveHeldSeatsQuietly(booking);

        bookingEventProducer.publishBookingConfirmed(booking, flightsOrNull(booking));

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
            bookingEventProducer.publishBookingConfirmed(booking, flightsOrNull(booking));
        }

        return booking;
    }

    public BookingResponse cancelBooking(Long id, String reason) {

        BookingResponse booking = bookingService.cancelBooking(id, reason);

        // Return the seats to the pool - holds if never confirmed,
        // reservations if it was. Cleanup must not fail the cancellation.
        for (BookingPassengerResponse passenger : booking.passengers()) {
            if (passenger.seatNumber() != null && !passenger.seatNumber().isBlank()) {
                inventoryServiceClient.releaseHoldQuietly(passenger.flightId(),
                        passenger.seatNumber(), booking.id(), "booking cancelled");
                inventoryServiceClient.cancelReservationQuietly(passenger.flightId(),
                        passenger.seatNumber(), booking.id(), "booking cancelled");
            }
        }

        bookingEventProducer.publishBookingCancelled(booking, flightsOrNull(booking));

        return booking;
    }

    /**
     * Cancel selected passengers (business rules 4-11). The service applies the
     * guardian rule and derives the booking status (PARTIALLY_CANCELLED, or
     * CANCELLED when the last passenger goes); this releases inventory ONLY for
     * the cancelled passengers (rule 6) and notifies on a full cancel.
     */
    public CancelPassengersResponse cancelPassengers(Long bookingId, java.util.List<Long> bookingPassengerIds) {

        CancelPassengersResponse result = bookingService.cancelPassengers(bookingId, bookingPassengerIds);
        BookingResponse booking = result.booking();

        // Release seats only for cancelled passengers - remaining passengers keep
        // theirs (rules 6, 7). Quiet + idempotent, so re-running is harmless.
        for (BookingPassengerResponse passenger : booking.passengers()) {
            if (passenger.cancelled() && passenger.seatNumber() != null && !passenger.seatNumber().isBlank()) {
                inventoryServiceClient.releaseHoldQuietly(passenger.flightId(),
                        passenger.seatNumber(), booking.id(), "passenger cancelled");
                inventoryServiceClient.cancelReservationQuietly(passenger.flightId(),
                        passenger.seatNumber(), booking.id(), "passenger cancelled");
            }
        }

        if (result.bookingCancelled()) {
            bookingEventProducer.publishBookingCancelled(booking, flightsOrNull(booking));
        }

        return result;
    }

    /**
     * Cancel just one segment - "drop the return" (ROUND_TRIP_MODULE.md §7).
     * Releases only that leg's seats; the event fires only if the booking
     * as a whole ended up cancelled, mirroring cancelPassengers.
     */
    public CancelPassengersResponse cancelSegment(Long bookingId, int segmentIndex) {

        CancelPassengersResponse result = bookingService.cancelSegment(bookingId, segmentIndex);
        BookingResponse booking = result.booking();

        for (BookingPassengerResponse passenger : booking.passengers()) {
            if (passenger.cancelled() && passenger.segmentIndex() == segmentIndex
                    && passenger.seatNumber() != null && !passenger.seatNumber().isBlank()) {
                inventoryServiceClient.releaseHoldQuietly(passenger.flightId(),
                        passenger.seatNumber(), booking.id(), "segment cancelled");
                inventoryServiceClient.cancelReservationQuietly(passenger.flightId(),
                        passenger.seatNumber(), booking.id(), "segment cancelled");
            }
        }

        if (result.bookingCancelled()) {
            bookingEventProducer.publishBookingCancelled(booking, flightsOrNull(booking));
        }

        return result;
    }

    /**
     * Premium date change (ROUND_TRIP_MODULE.md §11): move one segment to a
     * new flight on the SAME booking. Old seats release; replacement rows
     * auto-hold + reserve on the new flight (Premium seats are free, so the
     * charged surcharge is always 0 and no money moves for seats); the
     * refreshed CONFIRMED event lets checkin-service open per-direction
     * check-in for the new rows and close the exchanged ones.
     */
    public BookingResponse rebookSegment(Long bookingId, int segmentIndex, Long newFlightId) {

        FlightDetails newFlight = flightServiceClient.getFlight(newFlightId);
        if (newFlight.status() == FlightBookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot move the booking onto a cancelled flight");
        }

        // Chronology stays sane against the OTHER segment, best-effort.
        BookingResponse current = bookingService.getBookingById(bookingId);
        for (var other : current.segments()) {
            if (other.segmentIndex() == segmentIndex) {
                continue;
            }
            FlightDetails otherFlight = flightOrNull(other.flightId());
            if (otherFlight == null) {
                continue;
            }
            boolean ok = other.segmentIndex() < segmentIndex
                    ? newFlight.departureTime().isAfter(otherFlight.arrivalTime())
                    : otherFlight.departureTime().isAfter(newFlight.arrivalTime());
            if (!ok) {
                throw new IllegalArgumentException(
                        "The new flight clashes with the other leg of this trip - pick a date that keeps "
                                + "the outbound before the return.");
            }
        }

        // Seats to release AFTER the exchange commits - captured up front.
        List<BookingPassengerResponse> oldRows = current.passengers().stream()
                .filter(p -> !p.cancelled() && p.segmentIndex() == segmentIndex)
                .toList();

        BookingResponse rebooked = bookingService.rebookSegment(
                bookingId, segmentIndex, newFlightId, newFlight.departureTime());

        for (BookingPassengerResponse old : oldRows) {
            if (old.seatNumber() != null && !old.seatNumber().isBlank()) {
                inventoryServiceClient.releaseHoldQuietly(old.flightId(), old.seatNumber(),
                        bookingId, "segment rebooked");
                inventoryServiceClient.cancelReservationQuietly(old.flightId(), old.seatNumber(),
                        bookingId, "segment rebooked");
            }
        }

        // Auto-seat the replacement rows on the new flight - quiet, best
        // effort (a seatless row picks a seat at check-in instead).
        Map<Long, String> seatByRow = new java.util.HashMap<>();
        for (BookingPassengerResponse row : rebooked.passengers()) {
            if (row.cancelled() || row.segmentIndex() != segmentIndex || row.seatNumber() != null) {
                continue;
            }
            try {
                Optional<InventoryHoldDetails> hold = inventoryServiceClient.autoHoldSeat(
                        newFlightId, bookingId, row.id(), row.travelClass());
                if (hold.isPresent()) {
                    inventoryServiceClient.reserveSeat(newFlightId, hold.get().seatNumber(), bookingId, row.id());
                    seatByRow.put(row.id(), hold.get().seatNumber());
                }
            } catch (RuntimeException e) {
                log.warn("Could not auto-seat rebooked row {} on flight {}: {}", row.id(), newFlightId, e.getMessage());
            }
        }
        BookingResponse finalBooking = seatByRow.isEmpty()
                ? rebooked
                : bookingService.applySeatNumbers(bookingId, seatByRow);

        bookingEventProducer.publishBookingConfirmed(finalBooking, flightsOrNull(finalBooking));

        return finalBooking;
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
                        .map(cabin -> cabinQuote(cabin.travelClass(), cabin.availableSeats(), flight.departureTime()))
                        .toList())
                .orElseGet(() -> Arrays.stream(TravelClass.values())
                        .map(travelClass -> cabinQuote(travelClass, null, flight.departureTime()))
                        .toList());

        return new QuoteResponse(flightId, QUOTE_CURRENCY, cabins);
    }

    private QuoteResponse.CabinQuote cabinQuote(TravelClass travelClass, Integer availableSeats,
                                                java.time.LocalDateTime departureTime) {
        Map<FareType, BigDecimal> baseFares = new EnumMap<>(FareType.class);
        for (FareType fareType : FareType.values()) {
            baseFares.put(fareType, fareCalculator.calculateFare(travelClass, fareType, departureTime));
        }
        BigDecimal fromFare = baseFares.values().stream().min(BigDecimal::compareTo).orElseThrow();
        return new QuoteResponse.CabinQuote(travelClass, availableSeats, baseFares, fromFare);
    }

    /**
     * Per-date lowest fares for a route (the fare calendar): flight-service
     * says WHICH days have bookable departures, FareCalculator says what the
     * chosen cabin's cheapest fare costs on each - the same deterministic
     * formula every quote and booking uses, so the calendar can never disagree
     * with checkout. Public shopping data, like /quote.
     */
    public List<FareCalendarDayResponse> fareCalendar(String originAirportCode,
                                                      String destinationAirportCode,
                                                      java.time.LocalDate startDate,
                                                      java.time.LocalDate endDate,
                                                      TravelClass travelClass) {
        return flightServiceClient.getRouteCalendar(originAirportCode, destinationAirportCode, startDate, endDate)
                .stream()
                .map(day -> {
                    BigDecimal cheapest = Arrays.stream(FareType.values())
                            .map(fareType -> fareCalculator.calculateFare(
                                    travelClass, fareType, day.date().atStartOfDay()))
                            .min(BigDecimal::compareTo)
                            .orElseThrow();
                    return new FareCalendarDayResponse(day.date(), day.flights(), cheapest, QUOTE_CURRENCY);
                })
                .toList();
    }

    /**
     * Pre-check-in seat change (passenger features): after payment, before
     * check-in, a traveller moves seats under the SAME entitlement ceiling
     * check-in uses (§9) - Flexi/Premium move anywhere free (their picks are
     * waived), Saver up to the surcharge they originally PAID. Stored money
     * never moves. Hold-new-first ordering: the old seat is only released
     * once the new one is secured, so a failure can never leave the
     * traveller seatless.
     */
    public BookingResponse changeSeat(Long bookingId, Long bookingPassengerId, String seatNumber) {

        BookingResponse booking = bookingService.getBookingById(bookingId);
        BookingPassengerResponse row = booking.passengers().stream()
                .filter(p -> p.id().equals(bookingPassengerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such passenger on this booking"));

        if (booking.bookingStatus() != com.skybook.praveen.bookingservice.enums.BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Seats can be changed after payment and before check-in.");
        }
        if (row.cancelled()) {
            throw new IllegalStateException("This passenger has been cancelled off the booking.");
        }
        if (row.checkInStatus() == com.skybook.praveen.bookingservice.enums.CheckInStatus.CHECKED_IN
                || row.checkInStatus() == com.skybook.praveen.bookingservice.enums.CheckInStatus.BOARDED
                || row.checkInStatus() == com.skybook.praveen.bookingservice.enums.CheckInStatus.CLOSED) {
            throw new IllegalStateException(
                    "Already checked in - use the seat change at check-in instead.");
        }

        String newSeat = seatNumber.toUpperCase();
        if (newSeat.equals(row.seatNumber())) {
            return booking;
        }

        InventoryHoldDetails held = inventoryServiceClient.holdSeat(
                        row.flightId(), newSeat, bookingId, bookingPassengerId, row.travelClass())
                .orElseThrow(() -> new IllegalStateException("This flight has no seat inventory"));

        if (row.fareType() == FareType.SAVER
                && held.listedSurcharge().compareTo(row.seatSurcharge()) > 0) {
            inventoryServiceClient.releaseHoldQuietly(row.flightId(), newSeat, bookingId,
                    "seat change above fare ceiling");
            throw new IllegalArgumentException(
                    "Seat " + newSeat + " carries a " + held.listedSurcharge()
                            + " surcharge - above the " + row.seatSurcharge()
                            + " your Saver fare paid. Pick a seat at or below it.");
        }

        // New seat secured as a reservation (the booking is CONFIRMED), then
        // the old one goes back to the pool.
        inventoryServiceClient.reserveSeat(row.flightId(), newSeat, bookingId, bookingPassengerId);
        if (row.seatNumber() != null && !row.seatNumber().isBlank()) {
            inventoryServiceClient.releaseHoldQuietly(row.flightId(), row.seatNumber(), bookingId, "seat changed");
            inventoryServiceClient.cancelReservationQuietly(row.flightId(), row.seatNumber(), bookingId, "seat changed");
        }

        return bookingService.updateSeatNumber(bookingId, bookingPassengerId, newSeat);
    }

    // ---------------------------------------------------------------
    // Fare watch (passenger features)
    // ---------------------------------------------------------------

    /** Watch a route+date+cabin; the subject IS the email alerts go to. */
    public FareAlertResponse createFareAlert(String origin, String destination,
                                             java.time.LocalDate travelDate, TravelClass travelClass) {
        String subject = currentSubject();
        if (subject == null) {
            throw new IllegalStateException("A fare alert needs a signed-in owner");
        }
        if (travelDate.isBefore(java.time.LocalDate.now())) {
            throw new IllegalArgumentException("Cannot watch a date in the past");
        }
        FareAlert alert = fareAlertRepository.save(FareAlert.builder()
                .ownerSubject(subject)
                .originAirportCode(origin.toUpperCase())
                .destinationAirportCode(destination.toUpperCase())
                .travelDate(travelDate)
                .travelClass(travelClass)
                // Baseline = today's fare: the first mail is a real CHANGE,
                // never an echo of what the user just saw on screen.
                .lastNotifiedFare(cheapestFare(travelClass, travelDate))
                .active(true)
                .build());
        return toFareAlertResponse(alert);
    }

    public List<FareAlertResponse> myFareAlerts() {
        String subject = currentSubject();
        return subject == null ? List.of()
                : fareAlertRepository.findByOwnerSubjectAndActiveTrueOrderByTravelDateAsc(subject)
                        .stream().map(this::toFareAlertResponse).toList();
    }

    public void deleteFareAlert(Long id) {
        String subject = currentSubject();
        FareAlert alert = fareAlertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such fare alert"));
        if (subject == null || !subject.equals(alert.getOwnerSubject())) {
            throw new org.springframework.security.access.AccessDeniedException("Not your alert");
        }
        alert.setActive(false);
        fareAlertRepository.save(alert);
    }

    /** Cheapest fare across fare families - the calendar's own formula. */
    public BigDecimal cheapestFare(TravelClass travelClass, java.time.LocalDate date) {
        return Arrays.stream(FareType.values())
                .map(fareType -> fareCalculator.calculateFare(travelClass, fareType, date.atStartOfDay()))
                .min(BigDecimal::compareTo)
                .orElseThrow();
    }

    private FareAlertResponse toFareAlertResponse(FareAlert alert) {
        return new FareAlertResponse(alert.getId(), alert.getOriginAirportCode(),
                alert.getDestinationAirportCode(), alert.getTravelDate(), alert.getTravelClass(),
                cheapestFare(alert.getTravelClass(), alert.getTravelDate()),
                alert.getLastNotifiedFare(), QUOTE_CURRENCY);
    }

    /**
     * The authenticated JWT subject captured as the booking owner (§4.2). The
     * create endpoint is {@code authenticated()} (§13 step 4), so a real
     * principal is present for every new booking; null only defensively (e.g.
     * enforcement disabled in a test), which yields a legacy-style unowned row.
     */
    private String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken))
                ? auth.getName() : null;
    }

    /**
     * Flight context for email enrichment - best-effort by design: an email
     * without route details beats a confirmation that fails because
     * flight-service was briefly down.
     */
    /** One best-effort FlightDetails per segment, for event enrichment (§6). */
    private List<FlightDetails> flightsOrNull(BookingResponse booking) {
        if (booking.segments() == null || booking.segments().isEmpty()) {
            return List.of();
        }
        List<FlightDetails> flights = new ArrayList<>();
        for (var segment : booking.segments()) {
            FlightDetails details = flightOrNull(segment.flightId());
            if (details != null) {
                flights.add(details);
            }
        }
        return flights;
    }

    private FlightDetails flightOrNull(Long flightId) {
        try {
            // Service-token call: this runs during event publication, which for the
            // PAYMENT_SUCCEEDED->confirm path is a Kafka consumer thread with no
            // incoming user token to propagate (§3.3/§4.2).
            return flightServiceClient.getFlightAsService(flightId);
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
    /** A hold taken during the saga: which flight it lives on, and which seat - the unit of compensation. */
    private record HeldSeat(Long flightId, String seatNumber) {
    }

    private List<SeatAssignmentResult> holdSeatsOrCompensate(BookingResponse draft,
                                                             CreateBookingRequest request) {

        // Rows are segment-major (outbound rows first, then return rows), so
        // row i belongs to traveller i % travellerCount. ONE compensation
        // list spans every flight (ROUND_TRIP_MODULE.md §5): any failure on
        // either leg releases all holds taken on both. All-or-nothing.
        int travellerCount = request.passengers().size();
        List<SeatAssignmentResult> assignments = new ArrayList<>();
        List<HeldSeat> heldSeats = new ArrayList<>();
        try {
            for (int i = 0; i < draft.passengers().size(); i++) {

                BookingPassengerResponse passenger = draft.passengers().get(i);
                PassengerBookingDetail detail = request.passengers().get(i % travellerCount);
                // Each leg takes its own pick: seatNumber for the outbound,
                // returnSeatNumber for the return - absent means free AUTO.
                String requestedSeat = requestedSeatFor(passenger.segmentIndex(), request, detail);
                boolean manual = requestedSeat != null && !requestedSeat.isBlank();

                Optional<InventoryHoldDetails> hold = manual
                        ? inventoryServiceClient.holdSeat(passenger.flightId(),
                                requestedSeat.toUpperCase(), draft.id(), passenger.id(), detail.travelClass())
                        : inventoryServiceClient.autoHoldSeat(passenger.flightId(),
                                draft.id(), passenger.id(), detail.travelClass());

                if (hold.isEmpty()) {
                    // "No inventory" is a per-flight fact, so it is only
                    // acceptable BEFORE any hold was taken. After a successful
                    // hold it signals an inconsistent downstream state
                    // (review hardening) - never finalize the unpriced
                    // fallback while earlier passengers hold real seats.
                    if (heldSeats.isEmpty()) {
                        return noInventoryAssignments(draft, request);
                    }
                    throw new IllegalStateException("inventory reported no inventory for flight "
                            + passenger.flightId() + " after " + heldSeats.size()
                            + " seat(s) were already held - inconsistent inventory state");
                }

                InventoryHoldDetails held = hold.get();
                // Fare-family entitlement: Flexi and Premium include free seat
                // selection - the listed surcharge stays on record, but the
                // CHARGED amount is waived. Saver pays the listed price.
                BigDecimal charged = passenger.fareType() == FareType.SAVER
                        ? held.chargedSurcharge()
                        : ZERO_MONEY;
                assignments.add(new SeatAssignmentResult(
                        passenger.id(),
                        held.seatNumber(),
                        held.listedSurcharge(),
                        charged,
                        SeatAssignmentMode.valueOf(held.assignmentMode())));
                heldSeats.add(new HeldSeat(passenger.flightId(), held.seatNumber()));
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
            // Recover each held seat's flight through its passenger row - the
            // assignment carries only the row id, and on a round trip the
            // seats live on two different flights.
            Map<Long, Long> flightByRowId = new java.util.HashMap<>();
            for (BookingPassengerResponse row : draft.passengers()) {
                flightByRowId.put(row.id(), row.flightId());
            }
            List<HeldSeat> heldSeats = assignments.stream()
                    .filter(a -> a.seatNumber() != null && !a.seatNumber().isBlank())
                    .map(a -> new HeldSeat(flightByRowId.get(a.bookingPassengerId()), a.seatNumber()))
                    .toList();
            compensate(draft, heldSeats, "Finalization failed: " + finalizeFailure.getMessage());
            throw finalizeFailure;
        }
    }

    /** Hold-if-exists fallback: each leg's requested seat (manual) or none (auto), all charged 0. */
    private List<SeatAssignmentResult> noInventoryAssignments(BookingResponse draft,
                                                              CreateBookingRequest request) {
        int travellerCount = request.passengers().size();
        List<SeatAssignmentResult> assignments = new ArrayList<>();
        for (int i = 0; i < draft.passengers().size(); i++) {
            BookingPassengerResponse row = draft.passengers().get(i);
            PassengerBookingDetail detail = request.passengers().get(i % travellerCount);
            String requestedSeat = requestedSeatFor(row.segmentIndex(), request, detail);
            boolean manual = requestedSeat != null && !requestedSeat.isBlank();
            assignments.add(new SeatAssignmentResult(
                    row.id(),
                    manual ? requestedSeat.toUpperCase() : null,
                    ZERO_MONEY, ZERO_MONEY,
                    manual ? SeatAssignmentMode.MANUAL : SeatAssignmentMode.AUTO));
        }
        return assignments;
    }

    /**
     * The seat the traveller asked for on a given segment: segment 0 reads
     * seatNumber, a through-ticket onward leg reads its connectionSeatNumbers
     * entry, and the return segment reads returnSeatNumber. Null/blank = AUTO.
     */
    private static String requestedSeatFor(int segmentIndex, CreateBookingRequest request,
                                           PassengerBookingDetail detail) {
        if (segmentIndex == 0) {
            return detail.seatNumber();
        }
        int connectionLegs = request.connectionFlightIds() == null ? 0 : request.connectionFlightIds().size();
        if (segmentIndex <= connectionLegs) {
            List<String> seats = detail.connectionSeatNumbers();
            return seats != null && seats.size() >= segmentIndex ? seats.get(segmentIndex - 1) : null;
        }
        return detail.returnSeatNumber();
    }

    /** Release taken holds - on every flight involved - and cancel the draft (DRAFT -> CANCELLED, §5.1a). */
    private void compensate(BookingResponse draft, List<HeldSeat> heldSeats, String reason) {
        for (HeldSeat held : heldSeats) {
            inventoryServiceClient.releaseHoldQuietly(held.flightId(), held.seatNumber(), draft.id(),
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
                inventoryServiceClient.reserveSeat(passenger.flightId(), seat, booking.id(), passenger.id());
            } catch (RuntimeException e) {
                log.warn("Could not convert hold to reservation for seat {} on booking {}: {}",
                        seat, booking.bookingReference(), e.getMessage());
            }
        }
    }
}
