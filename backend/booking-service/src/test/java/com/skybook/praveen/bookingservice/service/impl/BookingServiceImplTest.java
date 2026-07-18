package com.skybook.praveen.bookingservice.service.impl;

import com.skybook.praveen.bookingservice.domain.BookingStateMachine;
import com.skybook.praveen.bookingservice.domain.BookingValidator;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.PnrGenerator;
import com.skybook.praveen.bookingservice.domain.SeatAssignmentResult;
import com.skybook.praveen.bookingservice.dto.request.BookingContactRequest;
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
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.BookingNotFoundException;
import com.skybook.praveen.bookingservice.exception.BookingPassengerNotFoundException;
import com.skybook.praveen.bookingservice.repository.BookingPassengerRepository;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repositories are mocked; the domain services (PnrGenerator,
 * BookingStateMachine, BookingValidator, FareCalculator) are real instances,
 * not mocks - they're pure/deterministic and already unit-tested on their own
 * (see the `domain` package), so exercising the real integration here is more
 * useful than restubbing every canTransition/calculateFare call.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    private static final long DRAFT_TTL_MINUTES = 15;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingPassengerRepository bookingPassengerRepository;

    private final PnrGenerator pnrGenerator = new PnrGenerator();
    private final BookingStateMachine bookingStateMachine = new BookingStateMachine();
    private final BookingValidator bookingValidator = new BookingValidator();
    private final FareCalculator fareCalculator = new FareCalculator();

    private BookingServiceImpl bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingServiceImpl(
                bookingRepository, bookingPassengerRepository,
                pnrGenerator, bookingStateMachine, bookingValidator, fareCalculator, DRAFT_TTL_MINUTES);
    }

    // ---------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------

    private PassengerBookingDetail passengerDetail(String seatNumber, TravelClass travelClass, FareType fareType) {
        return new PassengerBookingDetail(
                "Mr", "Jane", null, "Doe",
                LocalDate.of(1990, 1, 1), "FEMALE", "GBR",
                "P1234567", LocalDate.of(2032, 1, 1),
                "jane@example.com", "+441234567890",
                travelClass, fareType, seatNumber
        );
    }

    private CreateBookingRequest createRequest(List<PassengerBookingDetail> passengers) {
        return new CreateBookingRequest(
                100L, 1L, passengers,
                new BookingContactRequest("John Doe", "john@example.com", "+441234567891"),
                null
        );
    }

    private void stubSaveReturnsArgument() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------
    // createDraftBooking (stage 1 of draft -> hold -> finalize, §5.1)
    // ---------------------------------------------------------------

    @Nested
    class CreateDraftBooking {

        @Test
        void draftHasNoSeatNoPaymentAndBaseFareOnly() {
            when(bookingRepository.existsByBookingReference(anyString())).thenReturn(false);
            stubSaveReturnsArgument();

            CreateBookingRequest request = createRequest(
                    List.of(passengerDetail("12A", TravelClass.ECONOMY, FareType.FLEXI)));

            BookingResponse response = bookingService.createDraftBooking(request, LocalDateTime.now().plusDays(30), "owner@test.com");

            assertThat(response.bookingReference()).matches("^SB[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$");
            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.DRAFT);
            assertThat(response.customerId()).isEqualTo(100L);
            assertThat(response.flightId()).isEqualTo(1L);
            assertThat(response.totalFare()).isEqualByComparingTo("100.00");
            assertThat(response.passengers()).hasSize(1);
            // Seat resolution belongs to the facade's hold step, even when the
            // request named one - the draft never persists a seat.
            assertThat(response.passengers().get(0).seatNumber()).isNull();
            assertThat(response.passengers().get(0).baseFare()).isEqualByComparingTo("100.00");
            assertThat(response.passengers().get(0).seatSurcharge()).isEqualByComparingTo("0.00");
            assertThat(response.passengers().get(0).checkInStatus()).isEqualTo(CheckInStatus.NOT_OPEN);
            assertThat(response.contact().contactEmail()).isEqualTo("john@example.com");
            // No payment snapshot at draft (round 4) - finalize creates it.
            assertThat(response.payment()).isNull();
        }

        @Test
        void sumsUpBaseFaresAcrossMultiplePassengers() {
            when(bookingRepository.existsByBookingReference(anyString())).thenReturn(false);
            stubSaveReturnsArgument();

            CreateBookingRequest request = createRequest(List.of(
                    passengerDetail("12A", TravelClass.ECONOMY, FareType.FLEXI),   // 100.00
                    passengerDetail("12B", TravelClass.BUSINESS, FareType.SAVER)   // 350 * 0.85 = 297.50
            ));

            BookingResponse response = bookingService.createDraftBooking(request, LocalDateTime.now().plusDays(30), "owner@test.com");

            assertThat(response.passengers()).hasSize(2);
            assertThat(response.totalFare()).isEqualByComparingTo("397.50");
            assertThat(response.payment()).isNull();
        }

        @Test
        void blankSeatNumberIsLegalAtDraftStage() {
            // Blank seat = AUTO assignment (facade decides the mode) - the old
            // "Seat number is required" strategy rejection is gone with the
            // deleted strategy contract.
            when(bookingRepository.existsByBookingReference(anyString())).thenReturn(false);
            stubSaveReturnsArgument();

            CreateBookingRequest request = createRequest(
                    List.of(passengerDetail(null, TravelClass.ECONOMY, FareType.FLEXI)));

            BookingResponse response = bookingService.createDraftBooking(request, LocalDateTime.now().plusDays(30), "owner@test.com");

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.DRAFT);
            assertThat(response.passengers().get(0).seatNumber()).isNull();
        }

        @Test
        void rejectsWhenPassportExpiresBeforeTravelDate() {
            LocalDateTime departureTime = LocalDateTime.of(2026, 6, 1, 10, 0);

            PassengerBookingDetail expiredPassport = new PassengerBookingDetail(
                    "Mr", "Jane", null, "Doe",
                    LocalDate.of(1990, 1, 1), "FEMALE", "GBR",
                    "P1234567", LocalDate.of(2026, 5, 1), // expires before departure
                    "jane@example.com", "+441234567890",
                    TravelClass.ECONOMY, FareType.FLEXI, "12A"
            );

            CreateBookingRequest request = createRequest(List.of(expiredPassport));

            assertThatThrownBy(() -> bookingService.createDraftBooking(request, departureTime, "owner@test.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("passport");

            verify(bookingRepository, never()).save(any());
        }

        @Test
        void exhaustsRetriesWhenEveryGeneratedPnrCollides() {
            when(bookingRepository.existsByBookingReference(anyString())).thenReturn(true);

            CreateBookingRequest request = createRequest(
                    List.of(passengerDetail("12A", TravelClass.ECONOMY, FareType.FLEXI)));

            assertThatThrownBy(() -> bookingService.createDraftBooking(request, LocalDateTime.now().plusDays(30), "owner@test.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unique PNR");
        }

        @Test
        void retriesPnrGenerationOnCollisionUntilAUniqueOneIsFound() {
            PnrGenerator mockPnrGenerator = mock(PnrGenerator.class);
            when(mockPnrGenerator.generateCandidate()).thenReturn("SBAAAA", "SBAAAA", "SBBBBB");

            when(bookingRepository.existsByBookingReference("SBAAAA")).thenReturn(true);
            when(bookingRepository.existsByBookingReference("SBBBBB")).thenReturn(false);
            stubSaveReturnsArgument();

            BookingServiceImpl serviceWithMockedPnr = new BookingServiceImpl(
                    bookingRepository, bookingPassengerRepository,
                    mockPnrGenerator, bookingStateMachine, bookingValidator, fareCalculator, DRAFT_TTL_MINUTES);

            CreateBookingRequest request = createRequest(
                    List.of(passengerDetail("12A", TravelClass.ECONOMY, FareType.FLEXI)));

            BookingResponse response = serviceWithMockedPnr.createDraftBooking(request, LocalDateTime.now().plusDays(30), "owner@test.com");

            assertThat(response.bookingReference()).isEqualTo("SBBBBB");
        }
    }

    // ---------------------------------------------------------------
    // finalizeSeatAssignments (stage 3, §5.1) + stale-draft sweep (§5.1a)
    // ---------------------------------------------------------------

    @Nested
    class FinalizeAndSweep {

        private Booking draftWithOnePassenger() {
            BookingPassenger passenger = BookingPassenger.builder().id(10L)
                    .passenger(Passenger.builder().id(5L).firstName("Jane").lastName("Doe").build())
                    .flightId(1L).travelClass(TravelClass.ECONOMY).fareType(FareType.FLEXI)
                    .baseFare(new BigDecimal("100.00")).seatSurcharge(BigDecimal.ZERO)
                    .chargedSeatAssignmentMode(SeatAssignmentMode.MANUAL).currency("USD")
                    .fare(new BigDecimal("100.00")).checkInStatus(CheckInStatus.NOT_OPEN)
                    .build();
            Booking draft = Booking.builder().id(1L).bookingReference("SBDRFT").flightId(1L)
                    .bookingStatus(BookingStatus.DRAFT).bookingDate(LocalDateTime.now())
                    .totalFare(new BigDecimal("100.00"))
                    .passengers(new ArrayList<>(List.of(passenger))).history(new ArrayList<>())
                    .build();
            passenger.setBooking(draft);
            return draft;
        }

        @Test
        void finalizeWritesMoneyFieldsCreatesPaymentAndPromotesToCreated() {
            Booking draft = draftWithOnePassenger();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(draft));
            when(bookingPassengerRepository.findByIdAndBooking_Id(10L, 1L))
                    .thenReturn(Optional.of(draft.getPassengers().getFirst()));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.finalizeSeatAssignments(1L, List.of(
                    new SeatAssignmentResult(10L, "12A",
                            new BigDecimal("12.00"), new BigDecimal("12.00"), SeatAssignmentMode.MANUAL)));

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CREATED);
            assertThat(response.passengers().get(0).seatNumber()).isEqualTo("12A");
            assertThat(response.passengers().get(0).seatSurcharge()).isEqualByComparingTo("12.00");
            assertThat(response.passengers().get(0).fare()).isEqualByComparingTo("112.00");
            // Round-4 invariant: sum(passenger.fare) = totalFare = payment.amount.
            assertThat(response.totalFare()).isEqualByComparingTo("112.00");
            assertThat(response.payment().paymentStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(response.payment().amount()).isEqualByComparingTo("112.00");
        }

        @Test
        void autoAssignmentFinalizesAtChargedZeroEvenIfListedHigher() {
            Booking draft = draftWithOnePassenger();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(draft));
            when(bookingPassengerRepository.findByIdAndBooking_Id(10L, 1L))
                    .thenReturn(Optional.of(draft.getPassengers().getFirst()));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.finalizeSeatAssignments(1L, List.of(
                    new SeatAssignmentResult(10L, "14A",
                            new BigDecimal("12.00"), new BigDecimal("0.00"), SeatAssignmentMode.AUTO)));

            assertThat(response.passengers().get(0).seatSurcharge()).isEqualByComparingTo("0.00");
            assertThat(response.passengers().get(0).chargedSeatAssignmentMode()).isEqualTo(SeatAssignmentMode.AUTO);
            assertThat(response.totalFare()).isEqualByComparingTo("100.00");
            assertThat(response.payment().amount()).isEqualByComparingTo("100.00");
        }

        @Test
        void finalizeRejectsIncompletePassengerCoverage() {
            // Review follow-up: a malformed internal call must never promote
            // a booking while a passenger is seatless / still draft-priced.
            Booking draft = draftWithOnePassenger();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> bookingService.finalizeSeatAssignments(1L, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly");
            assertThat(draft.getBookingStatus()).isEqualTo(BookingStatus.DRAFT);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        void finalizeRejectsAssignmentsForForeignOrDuplicatePassengers() {
            Booking draft = draftWithOnePassenger();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(draft));

            // Foreign passenger id (999 is not on this booking).
            assertThatThrownBy(() -> bookingService.finalizeSeatAssignments(1L, List.of(
                    new SeatAssignmentResult(999L, "12A",
                            new BigDecimal("12.00"), new BigDecimal("12.00"), SeatAssignmentMode.MANUAL))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly");

            // Duplicate assignment for the same passenger.
            assertThatThrownBy(() -> bookingService.finalizeSeatAssignments(1L, List.of(
                    new SeatAssignmentResult(10L, "12A",
                            new BigDecimal("12.00"), new BigDecimal("12.00"), SeatAssignmentMode.MANUAL),
                    new SeatAssignmentResult(10L, "12B",
                            new BigDecimal("0.00"), new BigDecimal("0.00"), SeatAssignmentMode.AUTO))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate");
        }

        @Test
        void finalizeRejectsAssignmentsMissingModeOrCharge() {
            Booking draft = draftWithOnePassenger();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> bookingService.finalizeSeatAssignments(1L, List.of(
                    new SeatAssignmentResult(10L, "12A", new BigDecimal("12.00"),
                            new BigDecimal("12.00"), null))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mode/chargedSurcharge");
        }

        @Test
        void finalizeAcceptsANullSeatOnlyAsTheNoInventoryFallback() {
            // Documented fallback: no inventory record => AUTO with null seat
            // is legal; the coverage check must not reject it.
            Booking draft = draftWithOnePassenger();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(draft));
            when(bookingPassengerRepository.findByIdAndBooking_Id(10L, 1L))
                    .thenReturn(Optional.of(draft.getPassengers().getFirst()));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.finalizeSeatAssignments(1L, List.of(
                    new SeatAssignmentResult(10L, null,
                            new BigDecimal("0.00"), new BigDecimal("0.00"), SeatAssignmentMode.AUTO)));

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CREATED);
            assertThat(response.passengers().get(0).seatNumber()).isNull();
        }

        @Test
        void finalizeRejectsANonDraftBooking() {
            Booking created = draftWithOnePassenger();
            created.setBookingStatus(BookingStatus.CREATED);
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(created));

            assertThatThrownBy(() -> bookingService.finalizeSeatAssignments(1L, List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only a DRAFT");
        }

        @Test
        void aDraftCanNeverBeConfirmedDirectly() {
            // §5.1a: the crash-orphan window closes structurally - DRAFT ->
            // CONFIRMED is not a legal transition, so the back-office override
            // cannot confirm a seatless, paymentless draft.
            Booking draft = draftWithOnePassenger();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> bookingService.confirmBooking(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFT");
        }

        @Test
        void sweepCancelsDraftsPastTheirTtl() {
            Booking stale = draftWithOnePassenger();
            when(bookingRepository.findByBookingStatusAndBookingDateBefore(
                    eq(BookingStatus.DRAFT), any(LocalDateTime.class)))
                    .thenReturn(List.of(stale));
            stubSaveReturnsArgument();

            int swept = bookingService.cancelStaleDrafts();

            assertThat(swept).isEqualTo(1);
            assertThat(stale.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        }
    }

    // ---------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------

    @Nested
    class Reads {

        @Test
        void getBookingByIdReturnsMappedBooking() {
            Booking booking = Booking.builder().id(1L).bookingReference("SB1234").bookingStatus(BookingStatus.CREATED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>()).build();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));

            assertThat(bookingService.getBookingById(1L).bookingReference()).isEqualTo("SB1234");
        }

        @Test
        void getBookingByIdThrowsWhenNotFound() {
            when(bookingRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.getBookingById(1L))
                    .isInstanceOf(BookingNotFoundException.class);
        }

        @Test
        void getBookingByReferenceReturnsMappedBooking() {
            Booking booking = Booking.builder().id(1L).bookingReference("SB1234").bookingStatus(BookingStatus.CREATED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>()).build();
            when(bookingRepository.findByBookingReference("SB1234")).thenReturn(Optional.of(booking));

            assertThat(bookingService.getBookingByReference("SB1234").id()).isEqualTo(1L);
        }

        @Test
        void getBookingByReferenceThrowsWhenNotFound() {
            when(bookingRepository.findByBookingReference("SBZZZZ")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.getBookingByReference("SBZZZZ"))
                    .isInstanceOf(BookingNotFoundException.class)
                    .hasMessageContaining("SBZZZZ");
        }

        @Test
        void getAllBookingsReturnsEveryBookingMapped() {
            Booking b1 = Booking.builder().id(1L).bookingReference("SB1111").bookingStatus(BookingStatus.CREATED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>()).build();
            Booking b2 = Booking.builder().id(2L).bookingReference("SB2222").bookingStatus(BookingStatus.CREATED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>()).build();
            when(bookingRepository.findAll()).thenReturn(List.of(b1, b2));

            assertThat(bookingService.getAllBookings()).hasSize(2)
                    .extracting(BookingResponse::id).containsExactlyInAnyOrder(1L, 2L);
        }
    }

    // ---------------------------------------------------------------
    // searchBookings
    // ---------------------------------------------------------------

    @Nested
    class SearchBookings {

        private Booking booking1;
        private Booking booking2;

        @BeforeEach
        void seed() {
            Passenger p1 = Passenger.builder().id(1L).firstName("Jane").lastName("Doe").passportNumber("P1111").build();
            BookingPassenger bp1 = BookingPassenger.builder().passenger(p1).build();

            booking1 = Booking.builder().id(1L).bookingReference("SB1111").flightId(1L)
                    .bookingStatus(BookingStatus.CREATED).bookingDate(LocalDateTime.of(2026, 6, 1, 9, 0))
                    .passengers(new ArrayList<>(List.of(bp1))).history(new ArrayList<>())
                    .contact(BookingContact.builder().contactEmail("jane@example.com").contactPhone("111").build())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PENDING).build())
                    .build();

            Passenger p2 = Passenger.builder().id(2L).firstName("John").lastName("Smith").passportNumber("P2222").build();
            BookingPassenger bp2 = BookingPassenger.builder().passenger(p2).build();

            booking2 = Booking.builder().id(2L).bookingReference("SB2222").flightId(2L)
                    .bookingStatus(BookingStatus.CONFIRMED).bookingDate(LocalDateTime.of(2026, 6, 2, 9, 0))
                    .passengers(new ArrayList<>(List.of(bp2))).history(new ArrayList<>())
                    .contact(BookingContact.builder().contactEmail("john@example.com").contactPhone("222").build())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PAID).build())
                    .build();

            when(bookingRepository.findAll()).thenReturn(List.of(booking1, booking2));
        }

        @Test
        void filtersByBookingReference() {
            List<BookingResponse> results = bookingService.searchBookings(
                    new BookingSearchRequest("sb1111", null, null, null, null, null, null, null, null, null));

            assertThat(results).extracting(BookingResponse::id).containsExactly(1L);
        }

        @Test
        void filtersByBookingStatus() {
            List<BookingResponse> results = bookingService.searchBookings(
                    new BookingSearchRequest(null, null, null, null, BookingStatus.CONFIRMED, null, null, null, null, null));

            assertThat(results).extracting(BookingResponse::id).containsExactly(2L);
        }

        @Test
        void filtersByPassportNumber() {
            List<BookingResponse> results = bookingService.searchBookings(
                    new BookingSearchRequest(null, null, null, "p2222", null, null, null, null, null, null));

            assertThat(results).extracting(BookingResponse::id).containsExactly(2L);
        }

        @Test
        void filtersByPassengerNamePartialMatch() {
            List<BookingResponse> results = bookingService.searchBookings(
                    new BookingSearchRequest(null, null, "jane", null, null, null, null, null, null, null));

            assertThat(results).extracting(BookingResponse::id).containsExactly(1L);
        }

        @Test
        void filtersByContactEmail() {
            List<BookingResponse> results = bookingService.searchBookings(
                    new BookingSearchRequest(null, null, null, null, null, null, null, null, "john@example.com", null));

            assertThat(results).extracting(BookingResponse::id).containsExactly(2L);
        }

        @Test
        void returnsEverythingWhenNoFiltersGiven() {
            List<BookingResponse> results = bookingService.searchBookings(
                    new BookingSearchRequest(null, null, null, null, null, null, null, null, null, null));

            assertThat(results).hasSize(2);
        }
    }

    // ---------------------------------------------------------------
    // confirmBooking / cancelBooking / completeBooking
    // ---------------------------------------------------------------

    @Nested
    class ConfirmCancelComplete {

        @Test
        void confirmMarksPaymentPaidAndBookingConfirmed() {
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CREATED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PENDING).build())
                    .build();
            // BookingPayment.booking must be built before Booking exists, so the
            // back-reference has to be wired up after the fact - same reason
            // BookingPassenger needs an explicit setBooking() call below.
            booking.getPayment().setBooking(booking);
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.confirmBooking(1L);

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(response.payment().paymentStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        void cancelRefundsWhenPaymentWasCaptured() {
            BookingPassenger passenger = BookingPassenger.builder()
                    .passenger(Passenger.builder().id(5L).firstName("Jane").lastName("Doe").build())
                    .checkInStatus(CheckInStatus.CHECKED_IN).build();
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CONFIRMED)
                    .passengers(new ArrayList<>(List.of(passenger))).history(new ArrayList<>())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PAID).build())
                    .build();
            passenger.setBooking(booking);
            booking.getPayment().setBooking(booking);
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.cancelBooking(1L, "change of plans");

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(response.payment().paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(passenger.getCheckInStatus()).isEqualTo(CheckInStatus.CLOSED);
        }

        @Test
        void cancelDoesNotRefundWhenPaymentWasNeverCaptured() {
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CREATED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PENDING).build())
                    .build();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.cancelBooking(1L, null);

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(response.payment().paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        void completeTransitionsConfirmedToCompleted() {
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CONFIRMED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>()).build();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            stubSaveReturnsArgument();

            assertThat(bookingService.completeBooking(1L).bookingStatus()).isEqualTo(BookingStatus.COMPLETED);
        }

        @Test
        void throwsWhenBookingNotFound() {
            when(bookingRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.confirmBooking(1L)).isInstanceOf(BookingNotFoundException.class);
        }
    }

    // ---------------------------------------------------------------
    // checkInPassenger / boardPassenger
    // ---------------------------------------------------------------

    @Nested
    class CheckInAndBoard {

        @Test
        void checkInOpensWindowThenChecksInAConfirmedPaidBooking() {
            BookingPassenger passenger = BookingPassenger.builder().id(10L)
                    .passenger(Passenger.builder().id(5L).firstName("Jane").lastName("Doe").build())
                    .checkInStatus(CheckInStatus.NOT_OPEN).build();
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CONFIRMED)
                    .passengers(new ArrayList<>(List.of(passenger))).history(new ArrayList<>())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PAID).build())
                    .build();
            passenger.setBooking(booking);
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            when(bookingPassengerRepository.findByIdAndBooking_Id(10L, 1L)).thenReturn(Optional.of(passenger));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.checkInPassenger(1L, 10L);

            assertThat(response.passengers().get(0).checkInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
        }

        @Test
        void rejectsCheckInWhenBookingNotConfirmedAndPaid() {
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CREATED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PENDING).build())
                    .build();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.checkInPassenger(1L, 10L))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void throwsWhenPassengerDoesNotBelongToBooking() {
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CONFIRMED)
                    .passengers(new ArrayList<>()).history(new ArrayList<>())
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PAID).build())
                    .build();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            when(bookingPassengerRepository.findByIdAndBooking_Id(99L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.checkInPassenger(1L, 99L))
                    .isInstanceOf(BookingPassengerNotFoundException.class);
        }

        @Test
        void boardsACheckedInPassenger() {
            BookingPassenger passenger = BookingPassenger.builder().id(10L)
                    .passenger(Passenger.builder().id(5L).firstName("Jane").lastName("Doe").build())
                    .checkInStatus(CheckInStatus.CHECKED_IN).build();
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CONFIRMED)
                    .passengers(new ArrayList<>(List.of(passenger))).history(new ArrayList<>()).build();
            passenger.setBooking(booking);
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            when(bookingPassengerRepository.findByIdAndBooking_Id(10L, 1L)).thenReturn(Optional.of(passenger));
            stubSaveReturnsArgument();

            BookingResponse response = bookingService.boardPassenger(1L, 10L);

            assertThat(response.passengers().get(0).checkInStatus()).isEqualTo(CheckInStatus.BOARDED);
        }

        @Test
        void rejectsBoardingAPassengerWhoNeverCheckedIn() {
            BookingPassenger passenger = BookingPassenger.builder().id(10L).checkInStatus(CheckInStatus.NOT_OPEN).build();
            Booking booking = Booking.builder().id(1L).bookingStatus(BookingStatus.CONFIRMED)
                    .passengers(new ArrayList<>(List.of(passenger))).history(new ArrayList<>()).build();
            when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
            when(bookingPassengerRepository.findByIdAndBooking_Id(10L, 1L)).thenReturn(Optional.of(passenger));

            assertThatThrownBy(() -> bookingService.boardPassenger(1L, 10L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
