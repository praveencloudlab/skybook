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
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.entity.Passenger;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class BookingServiceImpl implements BookingService {

    private static final int MAX_PNR_GENERATION_ATTEMPTS = 10;
    private static final String DEFAULT_CURRENCY = "USD";

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
    public BookingResponse createDraftBooking(CreateBookingRequest request, LocalDateTime flightDepartureTime) {

        Booking booking = Booking.builder()
                .bookingReference(generateUniquePnr())
                .customerId(request.customerId())
                .flightId(request.flightId())
                .bookingStatus(BookingStatus.DRAFT)
                .bookingDate(LocalDateTime.now())
                .remarks(request.remarks())
                .build();

        List<BookingPassenger> bookingPassengers = new ArrayList<>();
        BigDecimal totalFare = BigDecimal.ZERO;

        for (PassengerBookingDetail detail : request.passengers()) {

            Passenger passenger = PassengerMapper.toEntity(detail);
            bookingValidator.validatePassportValidForTravel(passenger, flightDepartureTime);

            // Draft stage (§5.1): fare = base fare only, seat NULL, surcharge 0.
            // finalizeSeatAssignments writes the authoritative seat/surcharge/
            // mode from the inventory hold results. The MANUAL placeholder mode
            // exists only because the column is NOT NULL - it is meaningless
            // until finalize overwrites it.
            BigDecimal baseFare = fareCalculator.calculateFare(detail.travelClass(), detail.fareType());

            BookingPassenger bookingPassenger = BookingPassenger.builder()
                    .booking(booking)
                    .passenger(passenger)
                    .flightId(request.flightId())
                    .travelClass(detail.travelClass())
                    .fareType(detail.fareType())
                    .baseFare(baseFare)
                    .seatSurcharge(BigDecimal.ZERO)
                    .chargedSeatAssignmentMode(SeatAssignmentMode.MANUAL)
                    .currency(DEFAULT_CURRENCY)
                    .fare(baseFare)
                    .checkInStatus(CheckInStatus.NOT_OPEN)
                    .build();

            bookingPassengers.add(bookingPassenger);
            totalFare = totalFare.add(baseFare);
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

        BigDecimal totalFare = BigDecimal.ZERO;

        for (SeatAssignmentResult assignment : assignments) {

            BookingPassenger passenger = findBookingPassengerOrThrow(booking, assignment.bookingPassengerId());

            passenger.setSeatNumber(assignment.seatNumber());
            passenger.setSeatSurcharge(assignment.chargedSurcharge());
            passenger.setChargedSeatAssignmentMode(assignment.mode());
            passenger.setFare(passenger.getBaseFare().add(assignment.chargedSurcharge()));
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
