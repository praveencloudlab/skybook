package com.skybook.praveen.bookingservice.service.impl;

import com.skybook.praveen.bookingservice.domain.BookingStateMachine;
import com.skybook.praveen.bookingservice.domain.BookingValidator;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.PnrGenerator;
import com.skybook.praveen.bookingservice.domain.SeatAssignmentResult;
import com.skybook.praveen.bookingservice.dto.request.BookingSearchRequest;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.PassengerBookingDetail;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingContact;
import com.skybook.praveen.bookingservice.domain.PassengerCategory;
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.entity.BookingSegment;
import com.skybook.praveen.bookingservice.entity.Passenger;
import com.skybook.praveen.bookingservice.entity.Ticket;
import com.skybook.praveen.bookingservice.entity.TicketCoupon;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.bookingservice.enums.TicketStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.exception.BookingNotFoundException;
import com.skybook.praveen.bookingservice.exception.BookingPassengerNotFoundException;
import com.skybook.praveen.bookingservice.mapper.BookingMapper;
import com.skybook.praveen.bookingservice.mapper.PassengerMapper;
import com.skybook.praveen.bookingservice.repository.BookingPassengerRepository;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import com.skybook.praveen.bookingservice.service.BookingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BookingServiceImpl implements BookingService {

    private static final int MAX_PNR_GENERATION_ATTEMPTS = 10;
    private static final String DEFAULT_CURRENCY = "GBP";

    /** Bookings close this long before scheduled departure (check-in shuts at 45). */
    private static final long BOOKING_CLOSE_MINUTES = 60;

    /** Flat price per extra checked bag (ancillary), per passenger. */
    private static final BigDecimal EXTRA_BAG_FEE = new BigDecimal("40.00");

    private final BookingRepository bookingRepository;
    private final BookingPassengerRepository bookingPassengerRepository;

    private final PnrGenerator pnrGenerator;
    private final BookingStateMachine bookingStateMachine;
    private final BookingValidator bookingValidator;
    private final FareCalculator fareCalculator;

    /** TTL for the stale-draft sweep (§5.1a) - matches inventory's hold TTL by default. */
    private final long draftTtlMinutes;

    public BookingServiceImpl(BookingRepository bookingRepository,
                              BookingPassengerRepository bookingPassengerRepository,
                              PnrGenerator pnrGenerator,
                              BookingStateMachine bookingStateMachine,
                              BookingValidator bookingValidator,
                              FareCalculator fareCalculator,
                              @Value("${booking.draft.ttl-minutes:15}") long draftTtlMinutes) {
        this.bookingRepository = bookingRepository;
        this.bookingPassengerRepository = bookingPassengerRepository;
        this.pnrGenerator = pnrGenerator;
        this.bookingStateMachine = bookingStateMachine;
        this.bookingValidator = bookingValidator;
        this.fareCalculator = fareCalculator;
        this.draftTtlMinutes = draftTtlMinutes;
    }

    @Override
    @Transactional
    public BookingResponse createDraftBooking(CreateBookingRequest request, List<JourneyLeg> journey,
                                              String ownerSubject) {

        Booking booking = Booking.builder()
                .bookingReference(generateUniquePnr())
                .customerId(request.customerId())
                .flightId(request.flightId())
                .bookingStatus(BookingStatus.DRAFT)
                .bookingDate(LocalDateTime.now())
                .remarks(request.remarks())
                // Ownership captured from the authenticated principal (§4.2).
                .ownerSubject(ownerSubject)
                .build();

        // ONE Passenger identity per traveller, shared by their row on every
        // segment - the person flies both directions; only the per-leg
        // commercial data (seat, fare, bags, check-in) differs.
        List<Passenger> travellers = request.passengers().stream()
                .map(PassengerMapper::toEntity)
                .toList();

        List<BookingPassenger> bookingPassengers = new ArrayList<>();
        BigDecimal totalFare = BigDecimal.ZERO;

        // Segment-major row order (all direction-0 rows in leg order, then the
        // return's): the facade correlates row i with request detail
        // i % travellerCount, which this ordering guarantees.
        for (int segmentIndex = 0; segmentIndex < journey.size(); segmentIndex++) {
            JourneyLeg leg = journey.get(segmentIndex);

            // Authoritative bookability guard (not just UI hygiene): booking
            // closes 60 minutes before scheduled departure, so a departed or
            // imminently-departing flight is rejected here no matter what
            // client sent the request. Applies to every leg of the journey.
            if (leg.departureTime() != null
                    && !leg.departureTime().isAfter(LocalDateTime.now().plusMinutes(BOOKING_CLOSE_MINUTES))) {
                throw new IllegalArgumentException(
                        "Booking for flight " + leg.flightId() + " is closed - it departs (or departed) at "
                                + leg.departureTime() + ". Bookings close " + BOOKING_CLOSE_MINUTES
                                + " minutes before departure.");
            }

            BookingSegment segment = BookingSegment.builder()
                    .booking(booking)
                    .segmentIndex(segmentIndex)
                    .direction(leg.direction())
                    .flightId(leg.flightId())
                    .build();
            booking.getSegments().add(segment);

            for (int i = 0; i < request.passengers().size(); i++) {

                PassengerBookingDetail detail = request.passengers().get(i);
                Passenger passenger = travellers.get(i);
                bookingValidator.validatePassportValidForTravel(passenger, leg.departureTime());

                // Draft stage (§5.1): fare = base fare only, seat NULL, surcharge 0.
                // finalizeSeatAssignments writes the authoritative seat/surcharge/
                // mode from the inventory hold results. The MANUAL placeholder mode
                // exists only because the column is NOT NULL - it is meaningless
                // until finalize overwrites it.
                BigDecimal baseFare = fareCalculator.calculateFare(
                        detail.travelClass(), detail.fareType(), leg.departureTime());

                // Ancillary bags priced here, once, into the immutable breakdown -
                // refunds/invoices bill the stored fare, never a recomputation.
                // Bags charge once per DIRECTION (a through-ticket checks bags
                // through its connection), so only a direction's first leg
                // carries the count and the fee.
                int extraBags = leg.directionStart() && detail.extraBags() != null ? detail.extraBags() : 0;
                BigDecimal baggageFee = EXTRA_BAG_FEE.multiply(BigDecimal.valueOf(extraBags));

                BookingPassenger bookingPassenger = BookingPassenger.builder()
                        .booking(booking)
                        .passenger(passenger)
                        .segment(segment)
                        .flightId(leg.flightId())
                        .travelClass(detail.travelClass())
                        .fareType(detail.fareType())
                        .baseFare(baseFare)
                        .seatSurcharge(BigDecimal.ZERO)
                        .extraBags(extraBags)
                        .baggageFee(baggageFee)
                        .chargedSeatAssignmentMode(SeatAssignmentMode.MANUAL)
                        .currency(DEFAULT_CURRENCY)
                        .fare(baseFare.add(baggageFee))
                        .checkInStatus(CheckInStatus.NOT_OPEN)
                        .build();

                bookingPassengers.add(bookingPassenger);
                totalFare = totalFare.add(bookingPassenger.getFare());
            }
        }

        booking.setPassengers(bookingPassengers);
        booking.setTotalFare(totalFare);

        BookingContact contact = BookingContact.builder()
                .booking(booking)
                .contactName(request.contact().contactName())
                .contactEmail(request.contact().contactEmail())
                .contactPhone(request.contact().contactPhone())
                .build();
        booking.setContact(contact);

        // Deliberately NO BookingPayment here (round 4): the amount would go
        // stale the moment a surcharge lands. finalizeSeatAssignments creates
        // it with the final total in the same transaction as the money fields.

        Booking saved = bookingRepository.save(booking);

        log.info("Created DRAFT booking {} ({} passenger(s)) for flight {}",
                saved.getBookingReference(), bookingPassengers.size(), request.flightId());

        return BookingMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public BookingResponse finalizeSeatAssignments(Long bookingId, List<SeatAssignmentResult> assignments) {

        Booking booking = findBookingOrThrow(bookingId);

        if (booking.getBookingStatus() != BookingStatus.DRAFT) {
            throw new IllegalStateException("Booking " + booking.getBookingReference()
                    + " is " + booking.getBookingStatus() + " - only a DRAFT can be finalized");
        }

        validateCompleteCoverage(booking, assignments);

        BigDecimal totalFare = BigDecimal.ZERO;

        for (SeatAssignmentResult assignment : assignments) {

            BookingPassenger passenger = findBookingPassengerOrThrow(booking, assignment.bookingPassengerId());

            passenger.setSeatNumber(assignment.seatNumber());
            passenger.setSeatSurcharge(assignment.chargedSurcharge());
            passenger.setChargedSeatAssignmentMode(assignment.mode());
            // All-in fare: base + charged seat surcharge + baggage bought at
            // draft. Null-safe like the entity's own prePersist backfill: a row
            // created before the baggage column (or a bare test fixture) reads
            // as zero bags, not as a crash.
            BigDecimal baggageFee = passenger.getBaggageFee() != null
                    ? passenger.getBaggageFee()
                    : BigDecimal.ZERO;
            passenger.setFare(passenger.getBaseFare()
                    .add(assignment.chargedSurcharge())
                    .add(baggageFee));
        }

        for (BookingPassenger passenger : booking.getPassengers()) {
            totalFare = totalFare.add(passenger.getFare());
        }
        booking.setTotalFare(totalFare);

        // Payment snapshot created HERE, with the final total (round 4).
        // Invariant: sum(passenger.fare) = totalFare = payment.amount.
        BookingPayment payment = BookingPayment.builder()
                .booking(booking)
                .paymentStatus(PaymentStatus.PENDING)
                .amount(totalFare)
                .currency(DEFAULT_CURRENCY)
                .build();
        booking.setPayment(payment);

        bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CREATED,
                "seat assignments finalized (" + assignments.size() + " passenger(s))", "system");

        Booking saved = bookingRepository.save(booking);

        log.info("Finalized booking {} - total {} {} across {} passenger(s)",
                saved.getBookingReference(), totalFare, DEFAULT_CURRENCY, assignments.size());

        return BookingMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public int cancelStaleDrafts() {

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(draftTtlMinutes);
        List<Booking> stale = bookingRepository.findByBookingStatusAndBookingDateBefore(
                BookingStatus.DRAFT, cutoff);

        for (Booking draft : stale) {
            bookingStateMachine.transitionBookingStatus(draft, BookingStatus.CANCELLED,
                    "stale DRAFT swept (older than " + draftTtlMinutes + "m)", "system");
            bookingRepository.save(draft);
            log.info("Swept stale DRAFT booking {}", draft.getBookingReference());
        }

        return stale.size();
    }

    @Override
    public BookingResponse getBookingById(Long id) {
        return BookingMapper.toResponse(findBookingOrThrow(id));
    }

    @Override
    public BookingResponse getBookingByReference(String bookingReference) {
        Booking booking = bookingRepository.findByBookingReference(bookingReference)
                .orElseThrow(() -> new BookingNotFoundException(bookingReference));
        return BookingMapper.toResponse(booking);
    }

    @Override
    public List<BookingResponse> getAllBookings() {
        return bookingRepository.findAll().stream().map(BookingMapper::toResponse).toList();
    }

    @Override
    public List<BookingResponse> getBookingsForOwner(String ownerSubject) {
        // No ownership CHECK is needed here, because ownership is the query: a
        // caller can only ever ask for their own subject (the controller takes it
        // from the validated token, never from user input), so there is no id to
        // tamper with and nothing to compare after the fact.
        if (ownerSubject == null || ownerSubject.isBlank()) {
            return List.of();
        }
        return bookingRepository.findByOwnerSubjectOrderByBookingDateDesc(ownerSubject).stream()
                .map(BookingMapper::toResponse)
                .toList();
    }

    @Override
    public List<BookingResponse> searchBookings(BookingSearchRequest criteria) {
        return bookingRepository.findAll().stream()
                .filter(b -> criteria.bookingReference() == null
                        || criteria.bookingReference().equalsIgnoreCase(b.getBookingReference()))
                .filter(b -> criteria.flightId() == null || criteria.flightId().equals(b.getFlightId()))
                .filter(b -> criteria.bookingStatus() == null || b.getBookingStatus() == criteria.bookingStatus())
                .filter(b -> criteria.paymentStatus() == null
                        || (b.getPayment() != null && b.getPayment().getPaymentStatus() == criteria.paymentStatus()))
                .filter(b -> criteria.bookingDate() == null
                        || (b.getBookingDate() != null && b.getBookingDate().toLocalDate().isEqual(criteria.bookingDate())))
                .filter(b -> criteria.email() == null
                        || (b.getContact() != null && criteria.email().equalsIgnoreCase(b.getContact().getContactEmail())))
                .filter(b -> criteria.phone() == null
                        || (b.getContact() != null && criteria.phone().equals(b.getContact().getContactPhone())))
                .filter(b -> criteria.passportNumber() == null || b.getPassengers().stream().anyMatch(
                        p -> criteria.passportNumber().equalsIgnoreCase(p.getPassenger().getPassportNumber())))
                .filter(b -> criteria.passengerName() == null || b.getPassengers().stream().anyMatch(p ->
                        (p.getPassenger().getFirstName() + " " + p.getPassenger().getLastName())
                                .toLowerCase()
                                .contains(criteria.passengerName().toLowerCase())))
                .map(BookingMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public BookingResponse confirmBooking(Long id) {

        Booking booking = findBookingOrThrow(id);

        // Back-office override (Sprint 6): simulates a successful payment
        // directly. The normal path is confirmBookingFromPayment, driven by
        // payment-service's PAYMENT_SUCCEEDED event.
        if (booking.getPayment() != null) {
            bookingStateMachine.transitionPaymentStatus(booking.getPayment(), PaymentStatus.PAID, "system");
        }

        bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CONFIRMED,
                "manual confirmation (back-office override, simulated payment)", "system");

        issueTicketsIfAbsent(booking);

        return BookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Override
    @Transactional
    public PaymentConfirmation confirmBookingFromPayment(Long bookingId, String paymentReference) {

        Booking booking = findBookingOrThrow(bookingId);

        // Idempotent: a redelivered PAYMENT_SUCCEEDED finds the booking
        // already confirmed and changes nothing.
        if (booking.getBookingStatus() == BookingStatus.CONFIRMED) {
            log.info("Booking {} already CONFIRMED - duplicate payment event for {}",
                    booking.getBookingReference(), paymentReference);
            return new PaymentConfirmation(BookingMapper.toResponse(booking), false);
        }

        if (booking.getPayment() != null) {
            booking.getPayment().setExternalPaymentReference(paymentReference);
            bookingStateMachine.transitionPaymentStatus(booking.getPayment(), PaymentStatus.PAID,
                    "payment-service");
        }

        bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CONFIRMED,
                "payment " + paymentReference + " captured", "payment-service");

        issueTicketsIfAbsent(booking);

        log.info("Booking {} confirmed by payment {}", booking.getBookingReference(), paymentReference);

        return new PaymentConfirmation(BookingMapper.toResponse(bookingRepository.save(booking)), true);
    }

    @Override
    @Transactional
    public BookingResponse cancelBooking(Long id, String reason) {

        Booking booking = findBookingOrThrow(id);

        // Also cascades every passenger's CheckInStatus to CLOSED - see BookingStateMachine.
        bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CANCELLED, reason, "system");

        if (booking.getPayment() != null && booking.getPayment().getPaymentStatus() == PaymentStatus.PAID) {
            bookingValidator.validateRefundAllowed(booking);
            bookingStateMachine.transitionPaymentStatus(booking.getPayment(), PaymentStatus.REFUNDED, "system");
        }

        refundCoupons(booking, row -> true);

        return BookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Override
    @Transactional
    public CancelPassengersResponse cancelPassengers(Long bookingId, List<Long> bookingPassengerIds) {
        Booking booking = findBookingOrThrow(bookingId);

        List<BookingPassenger> active = booking.getPassengers().stream()
                .filter(bp -> !bp.isCancelled())
                .toList();
        Set<Long> ids = new HashSet<>(bookingPassengerIds);

        // Cancelling a passenger cancels them off EVERY segment
        // (ROUND_TRIP_MODULE.md §7): selecting any one of a traveller's
        // per-segment rows expands to all their rows - you can't fly out and
        // not exist on the return.
        Set<Long> travellerIds = active.stream()
                .filter(bp -> ids.contains(bp.getId()))
                .map(bp -> bp.getPassenger().getId())
                .collect(Collectors.toSet());

        List<BookingPassenger> toCancel = active.stream()
                .filter(bp -> ids.contains(bp.getId())
                        || (bp.getPassenger().getId() != null && travellerIds.contains(bp.getPassenger().getId())))
                .toList();
        if (toCancel.isEmpty()) {
            throw new IllegalStateException("None of the selected passengers are on this booking, or they are already cancelled.");
        }
        // A checked-in / boarded traveller cannot be cancelled online.
        for (BookingPassenger bp : toCancel) {
            if (bp.getCheckInStatus() == CheckInStatus.CHECKED_IN || bp.getCheckInStatus() == CheckInStatus.BOARDED) {
                throw new IllegalStateException(bp.getPassenger().getFirstName()
                        + " has already checked in and can no longer be cancelled online.");
            }
        }

        List<BookingPassenger> remaining = active.stream()
                .filter(bp -> !ids.contains(bp.getId()))
                .toList();

        // Guardian rule: a child/infant cannot remain on the booking without an
        // adult - so cancelling every adult must cancel the whole booking.
        if (!remaining.isEmpty()) {
            boolean minorRemains = remaining.stream()
                    .anyMatch(bp -> PassengerCategory.of(bp.getPassenger().getDob(), LocalDate.now()).isMinor());
            boolean adultRemains = remaining.stream()
                    .anyMatch(bp -> !PassengerCategory.of(bp.getPassenger().getDob(), LocalDate.now()).isMinor());
            if (minorRemains && !adultRemains) {
                throw new IllegalStateException(
                        "A child or infant can't remain on the booking without an adult. "
                                + "Cancel an accompanying adult too, or cancel the whole booking.");
            }
        }

        BigDecimal refundAmount = toCancel.stream()
                .map(BookingPassenger::getFare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (BookingPassenger bp : toCancel) {
            bp.setCancelled(true);
            bp.setCheckInStatus(CheckInStatus.CLOSED);
        }
        Set<Long> cancelledRowIds = toCancel.stream().map(BookingPassenger::getId)
                .collect(java.util.stream.Collectors.toSet());
        refundCoupons(booking, row -> cancelledRowIds.contains(row.getId()));

        boolean bookingCancelled = remaining.isEmpty();
        if (bookingCancelled) {
            // The last active passengers went - the booking itself is now
            // cancelled and (if paid) fully refunded, exactly like a whole cancel.
            bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CANCELLED,
                    "all passengers cancelled", "system");
            if (booking.getPayment() != null && booking.getPayment().getPaymentStatus() == PaymentStatus.PAID) {
                bookingValidator.validateRefundAllowed(booking);
                bookingStateMachine.transitionPaymentStatus(booking.getPayment(), PaymentStatus.REFUNDED, "system");
            }
        } else {
            // Booking lives on (rule 9): status is DERIVED as PARTIALLY_CANCELLED,
            // its value is now the sum of the remaining fares, and refunds are
            // calculated only for the cancelled passengers. The remaining
            // passengers' check-in is untouched (rule 7) - only the whole-cancel
            // path cascades check-in closure.
            if (booking.getBookingStatus() != BookingStatus.PARTIALLY_CANCELLED) {
                bookingStateMachine.transitionBookingStatus(booking, BookingStatus.PARTIALLY_CANCELLED,
                        "passenger(s) cancelled", "system");
            }
            BigDecimal remainingTotal = remaining.stream()
                    .map(BookingPassenger::getFare)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            booking.setTotalFare(remainingTotal);
        }

        Booking saved = bookingRepository.save(booking);
        return new CancelPassengersResponse(BookingMapper.toResponse(saved), refundAmount, bookingCancelled);
    }

    @Override
    @Transactional
    public CancelPassengersResponse cancelSegment(Long bookingId, int segmentIndex) {
        Booking booking = findBookingOrThrow(bookingId);

        BookingSegment segment = booking.getSegments().stream()
                .filter(s -> s.getSegmentIndex() == segmentIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Booking " + booking.getBookingReference() + " has no segment " + segmentIndex));

        // §7, keyed on DIRECTION since through-ticketing: only the return may
        // go alone. Dropping the outbound while keeping the return is the
        // no-show trap airlines void tickets over, and dropping ONE LEG of a
        // through-ticketed outbound connection would strand the journey.
        if (segment.getDirection() != 1) {
            throw new IllegalStateException(
                    "Only the return can be cancelled on its own - cancel the whole booking, "
                            + "or change the flight instead.");
        }

        List<BookingPassenger> toCancel = booking.getPassengers().stream()
                .filter(bp -> !bp.isCancelled())
                .filter(bp -> bp.getSegment() != null && segment.getId() != null
                        ? segment.getId().equals(bp.getSegment().getId())
                        : bp.getSegment() == segment)
                .toList();
        if (toCancel.isEmpty()) {
            throw new IllegalStateException("This segment is already fully cancelled.");
        }
        for (BookingPassenger bp : toCancel) {
            if (bp.getCheckInStatus() == CheckInStatus.CHECKED_IN || bp.getCheckInStatus() == CheckInStatus.BOARDED) {
                throw new IllegalStateException(bp.getPassenger().getFirstName()
                        + " has already checked in on this flight - the segment can no longer be cancelled online.");
            }
        }

        BigDecimal refundAmount = toCancel.stream()
                .map(BookingPassenger::getFare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (BookingPassenger bp : toCancel) {
            bp.setCancelled(true);
            bp.setCheckInStatus(CheckInStatus.CLOSED);
        }
        Set<Long> cancelledRows = toCancel.stream().map(BookingPassenger::getId).collect(Collectors.toSet());
        refundCoupons(booking, row -> cancelledRows.contains(row.getId()));

        List<BookingPassenger> remaining = booking.getPassengers().stream()
                .filter(bp -> !bp.isCancelled())
                .toList();
        boolean bookingCancelled = remaining.isEmpty();
        if (bookingCancelled) {
            bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CANCELLED,
                    "segment " + segmentIndex + " was the last active segment", "system");
            if (booking.getPayment() != null && booking.getPayment().getPaymentStatus() == PaymentStatus.PAID) {
                bookingValidator.validateRefundAllowed(booking);
                bookingStateMachine.transitionPaymentStatus(booking.getPayment(), PaymentStatus.REFUNDED, "system");
            }
        } else {
            if (booking.getBookingStatus() != BookingStatus.PARTIALLY_CANCELLED) {
                bookingStateMachine.transitionBookingStatus(booking, BookingStatus.PARTIALLY_CANCELLED,
                        "segment " + segmentIndex + " cancelled", "system");
            }
            booking.setTotalFare(remaining.stream()
                    .map(BookingPassenger::getFare)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        Booking saved = bookingRepository.save(booking);
        log.info("Cancelled segment {} of booking {} - {} row(s), refund {}",
                segmentIndex, saved.getBookingReference(), toCancel.size(), refundAmount);
        return new CancelPassengersResponse(BookingMapper.toResponse(saved), refundAmount, bookingCancelled);
    }

    @Override
    @Transactional
    public BookingResponse rebookSegment(Long bookingId, int segmentIndex, Long newFlightId,
                                         LocalDateTime newDepartureTime) {
        Booking booking = findBookingOrThrow(bookingId);

        if (booking.getBookingStatus() != BookingStatus.CONFIRMED
                && booking.getBookingStatus() != BookingStatus.PARTIALLY_CANCELLED) {
            throw new IllegalStateException("Only a confirmed booking can change flight dates online.");
        }
        BookingSegment segment = booking.getSegments().stream()
                .filter(s -> s.getSegmentIndex() == segmentIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Booking " + booking.getBookingReference() + " has no segment " + segmentIndex));

        List<BookingPassenger> oldRows = booking.getPassengers().stream()
                .filter(bp -> !bp.isCancelled())
                .filter(bp -> bp.getSegment() != null && segment.getId() != null
                        ? segment.getId().equals(bp.getSegment().getId())
                        : bp.getSegment() == segment)
                .toList();
        if (oldRows.isEmpty()) {
            throw new IllegalStateException("This segment is cancelled - there is nothing to rebook.");
        }
        for (BookingPassenger bp : oldRows) {
            if (bp.getCheckInStatus() == CheckInStatus.CHECKED_IN || bp.getCheckInStatus() == CheckInStatus.BOARDED) {
                throw new IllegalStateException(bp.getPassenger().getFirstName()
                        + " has already checked in on this flight - the date can no longer be changed online.");
            }
            // The entitlement with teeth (ROUND_TRIP_MODULE.md §11): Premium
            // changes dates online for just the fare difference; other fare
            // families go through cancel + rebook.
            if (bp.getFareType() != com.skybook.praveen.bookingservice.enums.FareType.PREMIUM) {
                throw new IllegalStateException(
                        "Online date changes are a Premium fare benefit - use Change flight (cancel and rebook) instead.");
            }
        }

        BigDecimal oldFares = BigDecimal.ZERO;
        BigDecimal newFares = BigDecimal.ZERO;
        List<BookingPassenger> newRows = new ArrayList<>();
        for (BookingPassenger old : oldRows) {
            old.setCancelled(true);
            old.setCheckInStatus(CheckInStatus.CLOSED);
            oldFares = oldFares.add(old.getFare());

            BigDecimal baseFare = fareCalculator.calculateFare(old.getTravelClass(), old.getFareType(),
                    newDepartureTime);
            BigDecimal baggageFee = old.getBaggageFee() != null ? old.getBaggageFee() : BigDecimal.ZERO;
            BookingPassenger replacement = BookingPassenger.builder()
                    .booking(booking)
                    .passenger(old.getPassenger())
                    .segment(segment)
                    .flightId(newFlightId)
                    .travelClass(old.getTravelClass())
                    .fareType(old.getFareType())
                    .baseFare(baseFare)
                    .seatSurcharge(BigDecimal.ZERO)
                    .extraBags(old.getExtraBags())
                    .baggageFee(baggageFee)
                    .chargedSeatAssignmentMode(SeatAssignmentMode.AUTO)
                    .currency(old.getCurrency())
                    .fare(baseFare.add(baggageFee))
                    .checkInStatus(CheckInStatus.NOT_OPEN)
                    .build();
            newRows.add(replacement);
            newFares = newFares.add(replacement.getFare());
        }
        booking.getPassengers().addAll(newRows);
        segment.setFlightId(newFlightId);

        // Exchange, not refund: old coupons go CANCELLED; each ticket gains a
        // fresh OPEN coupon for its traveller's replacement row.
        Set<Long> exchangedRows = oldRows.stream().map(BookingPassenger::getId).collect(Collectors.toSet());
        for (Ticket ticket : booking.getTickets()) {
            int nextCoupon = ticket.getCoupons().stream()
                    .mapToInt(TicketCoupon::getCouponNumber).max().orElse(0) + 1;
            for (TicketCoupon coupon : ticket.getCoupons()) {
                if (exchangedRows.contains(coupon.getBookingPassenger().getId())
                        && coupon.getStatus() != CouponStatus.FLOWN) {
                    coupon.setStatus(CouponStatus.CANCELLED);
                }
            }
            for (BookingPassenger replacement : newRows) {
                if (replacement.getPassenger() == ticket.getPassenger()
                        || (ticket.getPassenger().getId() != null
                            && ticket.getPassenger().getId().equals(replacement.getPassenger().getId()))) {
                    ticket.getCoupons().add(TicketCoupon.builder()
                            .ticket(ticket)
                            .bookingPassenger(replacement)
                            .couponNumber(nextCoupon++)
                            .status(CouponStatus.OPEN)
                            .build());
                }
            }
        }

        // Fare difference (simulated processor): totalFare and the payment
        // snapshot move to the new total; history records the delta.
        BigDecimal newTotal = booking.getPassengers().stream()
                .filter(bp -> !bp.isCancelled())
                .map(BookingPassenger::getFare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        booking.setTotalFare(newTotal);
        if (booking.getPayment() != null) {
            booking.getPayment().setAmount(newTotal);
        }
        BigDecimal difference = newFares.subtract(oldFares);
        bookingStateMachine.recordCustomHistory(booking,
                "Premium date change: segment " + segmentIndex + " moved to flight " + newFlightId
                        + " (fare difference " + (difference.signum() >= 0 ? "+" : "") + difference + ")",
                "system");

        Booking saved = bookingRepository.save(booking);
        log.info("Rebooked segment {} of booking {} onto flight {} (fare difference {})",
                segmentIndex, saved.getBookingReference(), newFlightId, difference);
        return BookingMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public BookingResponse applySeatNumbers(Long bookingId, Map<Long, String> seatByRowId) {
        Booking booking = findBookingOrThrow(bookingId);
        for (BookingPassenger bp : booking.getPassengers()) {
            String seat = seatByRowId.get(bp.getId());
            if (seat != null && !seat.isBlank()) {
                bp.setSeatNumber(seat);
            }
        }
        return BookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Override
    @Transactional
    public BookingResponse completeBooking(Long id) {

        Booking booking = findBookingOrThrow(id);
        bookingStateMachine.transitionBookingStatus(booking, BookingStatus.COMPLETED, null, "system");

        return BookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Override
    @Transactional
    public BookingResponse checkInPassenger(Long bookingId, Long bookingPassengerId) {

        Booking booking = findBookingOrThrow(bookingId);
        bookingValidator.validateCheckInAllowed(booking);

        BookingPassenger passenger = findBookingPassengerOrThrow(booking, bookingPassengerId);

        // No separate trigger opens the check-in window yet (docs section 11),
        // so checking in implicitly opens it first if it hasn't been already -
        // both steps are still recorded in BookingHistory.
        if (passenger.getCheckInStatus() == CheckInStatus.NOT_OPEN) {
            bookingStateMachine.transitionCheckInStatus(passenger, CheckInStatus.OPEN, "system");
        }

        bookingStateMachine.transitionCheckInStatus(passenger, CheckInStatus.CHECKED_IN, "system");

        markCouponCheckedIn(booking, passenger);

        return BookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Override
    @Transactional
    public BookingResponse boardPassenger(Long bookingId, Long bookingPassengerId) {

        Booking booking = findBookingOrThrow(bookingId);
        BookingPassenger passenger = findBookingPassengerOrThrow(booking, bookingPassengerId);

        bookingStateMachine.transitionCheckInStatus(passenger, CheckInStatus.BOARDED, "system");

        return BookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Override
    @Transactional
    public void applyCheckInStatus(Long bookingId, Long bookingPassengerId, CheckInStatus target) {

        Booking booking = findBookingOrThrow(bookingId);
        BookingPassenger passenger = findBookingPassengerOrThrow(booking, bookingPassengerId);
        CheckInStatus from = passenger.getCheckInStatus();

        // Replays and duplicate deliveries are normal for a read-model - a
        // no-op, not an error.
        if (from == target) {
            return;
        }

        // checkin-service never announces the window opening, so its terminal
        // facts arrive while the mirror still says NOT_OPEN - step through
        // OPEN exactly like checkInPassenger does, keeping history coherent.
        if (passenger.getCheckInStatus() == CheckInStatus.NOT_OPEN
                && (target == CheckInStatus.CHECKED_IN || target == CheckInStatus.NO_SHOW)) {
            bookingStateMachine.transitionCheckInStatus(passenger, CheckInStatus.OPEN, "checkin-service");
        }

        // An out-of-order or late event (e.g. CHECKED_IN arriving after this
        // passenger was cancelled and CLOSED) must not poison the topic - the
        // mirror simply keeps its more advanced state.
        if (!bookingStateMachine.canTransitionCheckIn(passenger.getCheckInStatus(), target)) {
            log.warn("Ignoring check-in mirror {} -> {} for passenger {} on booking {} (not a legal transition)",
                    passenger.getCheckInStatus(), target, bookingPassengerId, booking.getBookingReference());
            return;
        }

        bookingStateMachine.transitionCheckInStatus(passenger, target, "checkin-service");
        if (target == CheckInStatus.CHECKED_IN) {
            markCouponCheckedIn(booking, passenger);
        }
        bookingRepository.save(booking);
    }

    // ---------------------------------------------------------------
    // Tickets & coupons (ROUND_TRIP_MODULE.md)
    // ---------------------------------------------------------------

    /**
     * Issue e-tickets at CONFIRMED: one per traveller covering their whole
     * journey, one coupon per per-segment row (coupon 1 = outbound).
     * Idempotent - a redelivered confirmation finds tickets present and does
     * nothing; the DETERMINISTIC number (125 + booking id + traveller index)
     * would re-derive identically anyway.
     */
    private void issueTicketsIfAbsent(Booking booking) {
        if (!booking.getTickets().isEmpty()) {
            return;
        }
        Map<Long, List<BookingPassenger>> rowsByTraveller = new LinkedHashMap<>();
        for (BookingPassenger row : booking.getPassengers()) {
            rowsByTraveller.computeIfAbsent(row.getPassenger().getId(), k -> new ArrayList<>()).add(row);
        }
        int travellerIndex = 0;
        for (List<BookingPassenger> rows : rowsByTraveller.values()) {
            travellerIndex++;
            Ticket ticket = Ticket.builder()
                    .booking(booking)
                    .passenger(rows.get(0).getPassenger())
                    .ticketNumber(String.format("125%08d%02d", booking.getId(), travellerIndex))
                    .status(TicketStatus.ISSUED)
                    .issuedAt(LocalDateTime.now())
                    .build();
            int couponNumber = 1;
            for (BookingPassenger row : rows) {
                ticket.getCoupons().add(TicketCoupon.builder()
                        .ticket(ticket)
                        .bookingPassenger(row)
                        .couponNumber(couponNumber++)
                        .status(row.isCancelled() ? CouponStatus.CANCELLED : CouponStatus.OPEN)
                        .build());
            }
            booking.getTickets().add(ticket);
            log.info("Issued ticket {} for booking {} ({} coupon(s))",
                    ticket.getTicketNumber(), booking.getBookingReference(), ticket.getCoupons().size());
        }
    }

    /**
     * Cancellation-side coupon lifecycle: every matching row's coupon goes
     * REFUNDED (a FLOWN coupon is history and stays); a ticket with no
     * live coupon left is itself REFUNDED. No tickets (pre-ticketing booking
     * or an unconfirmed draft being compensated) = no-op.
     */
    private void refundCoupons(Booking booking, java.util.function.Predicate<BookingPassenger> affected) {
        for (Ticket ticket : booking.getTickets()) {
            for (TicketCoupon coupon : ticket.getCoupons()) {
                if (affected.test(coupon.getBookingPassenger()) && coupon.getStatus() != CouponStatus.FLOWN) {
                    coupon.setStatus(CouponStatus.REFUNDED);
                }
            }
            boolean anyLive = ticket.getCoupons().stream()
                    .anyMatch(c -> c.getStatus() == CouponStatus.OPEN || c.getStatus() == CouponStatus.CHECKED_IN);
            if (!anyLive && ticket.getStatus() == TicketStatus.ISSUED) {
                ticket.setStatus(TicketStatus.REFUNDED);
            }
        }
    }

    /** Check-in mirror onto the coupon: the row's coupon follows it to CHECKED_IN. */
    private void markCouponCheckedIn(Booking booking, BookingPassenger row) {
        booking.getTickets().stream()
                .flatMap(ticket -> ticket.getCoupons().stream())
                .filter(coupon -> coupon.getBookingPassenger().getId().equals(row.getId()))
                .filter(coupon -> coupon.getStatus() == CouponStatus.OPEN)
                .forEach(coupon -> coupon.setStatus(CouponStatus.CHECKED_IN));
    }

    /**
     * Finalization must cover EVERY passenger exactly once with complete
     * pricing (review follow-up on §5.1): a malformed internal call must not
     * create a payment and promote a booking while a passenger is seatless or
     * still carries draft placeholder pricing. seatNumber alone may be null -
     * the documented no-inventory AUTO fallback.
     */
    private void validateCompleteCoverage(Booking booking, List<SeatAssignmentResult> assignments) {

        Set<Long> assignedIds = new HashSet<>();
        for (SeatAssignmentResult assignment : assignments) {
            if (assignment.mode() == null || assignment.chargedSurcharge() == null) {
                throw new IllegalArgumentException("Assignment for passenger "
                        + assignment.bookingPassengerId() + " is missing mode/chargedSurcharge");
            }
            if (!assignedIds.add(assignment.bookingPassengerId())) {
                throw new IllegalArgumentException("Duplicate assignment for passenger "
                        + assignment.bookingPassengerId());
            }
        }

        Set<Long> passengerIds = booking.getPassengers().stream()
                .map(BookingPassenger::getId)
                .collect(Collectors.toSet());

        if (!assignedIds.equals(passengerIds)) {
            throw new IllegalArgumentException("Assignments must cover booking "
                    + booking.getBookingReference() + "'s passengers exactly: expected "
                    + passengerIds + " but got " + assignedIds);
        }
    }

    private String generateUniquePnr() {

        for (int attempt = 0; attempt < MAX_PNR_GENERATION_ATTEMPTS; attempt++) {
            String candidate = pnrGenerator.generateCandidate();
            if (!bookingRepository.existsByBookingReference(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "Could not generate a unique PNR after " + MAX_PNR_GENERATION_ATTEMPTS + " attempts");
    }

    private Booking findBookingOrThrow(Long id) {
        return bookingRepository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));
    }

    private BookingPassenger findBookingPassengerOrThrow(Booking booking, Long bookingPassengerId) {
        return bookingPassengerRepository.findByIdAndBooking_Id(bookingPassengerId, booking.getId())
                .orElseThrow(() -> new BookingPassengerNotFoundException(booking.getId(), bookingPassengerId));
    }
}
