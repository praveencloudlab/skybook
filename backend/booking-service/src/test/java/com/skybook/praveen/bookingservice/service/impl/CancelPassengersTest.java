package com.skybook.praveen.bookingservice.service.impl;

import com.skybook.praveen.bookingservice.domain.BookingStateMachine;
import com.skybook.praveen.bookingservice.domain.BookingValidator;
import com.skybook.praveen.bookingservice.domain.CancellationPolicy;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.PnrGenerator;
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;
import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.entity.BookingSegment;
import com.skybook.praveen.bookingservice.entity.Passenger;
import com.skybook.praveen.bookingservice.entity.Ticket;
import com.skybook.praveen.bookingservice.entity.TicketCoupon;
import com.skybook.praveen.bookingservice.enums.BookingHistoryField;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TicketStatus;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.repository.BookingPassengerRepository;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Passenger-level cancellation (business rules 4-11) with the REAL state
 * machine, validator and cancellation policy: who may be cancelled, what the
 * refund composes to, and what the surviving booking is worth afterwards.
 */
@ExtendWith(MockitoExtension.class)
class CancelPassengersTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingPassengerRepository bookingPassengerRepository;
    @Mock
    private PnrGenerator pnrGenerator;

    private BookingServiceImpl bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingServiceImpl(
                bookingRepository, bookingPassengerRepository,
                pnrGenerator, new BookingStateMachine(), new BookingValidator(),
                new FareCalculator(Clock.fixed(Instant.parse("2030-06-04T09:00:00Z"), ZoneOffset.UTC)),
                new CancellationPolicy(new BigDecimal("30"), 72, 24, 2, 6), 15);
        lenient().when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------

    private Booking booking(BookingStatus status, PaymentStatus paymentStatus) {
        Booking booking = Booking.builder()
                .id(7L).bookingReference("SBPAXC").customerId(1L).flightId(10L)
                .bookingStatus(status).bookingDate(LocalDateTime.now())
                .totalFare(new BigDecimal("200.00"))
                .build();
        booking.setPassengers(new ArrayList<>());
        booking.setHistory(new ArrayList<>());
        booking.setPayment(BookingPayment.builder().id(3L).booking(booking)
                .paymentStatus(paymentStatus).amount(new BigDecimal("200.00")).currency("GBP").build());
        return booking;
    }

    private Passenger traveller(long id, String firstName, int ageInYears) {
        return Passenger.builder().id(id).firstName(firstName).lastName("Test")
                .dob(LocalDate.now().minusYears(ageInYears)).build();
    }

    private BookingSegment segment(Booking booking, long id, int index, int direction, long flightId) {
        BookingSegment segment = BookingSegment.builder()
                .id(id).booking(booking).segmentIndex(index).direction(direction).flightId(flightId).build();
        booking.getSegments().add(segment);
        return segment;
    }

    private BookingPassenger row(Booking booking, long id, Passenger passenger, BookingSegment segment,
                                 FareType fareType, String fare, CheckInStatus checkInStatus) {
        BookingPassenger row = BookingPassenger.builder()
                .id(id).booking(booking).passenger(passenger).segment(segment)
                .flightId(segment.getFlightId())
                .travelClass(TravelClass.ECONOMY).fareType(fareType).seatNumber("12A")
                .baseFare(new BigDecimal(fare)).seatSurcharge(BigDecimal.ZERO)
                .extraBags(0).baggageFee(BigDecimal.ZERO)
                .chargedSeatAssignmentMode(SeatAssignmentMode.AUTO).currency("GBP")
                .fare(new BigDecimal(fare)).checkInStatus(checkInStatus).cancelled(false)
                .build();
        booking.getPassengers().add(row);
        return row;
    }

    private Ticket ticket(Booking booking, long id, Passenger passenger, BookingPassenger... rows) {
        Ticket ticket = Ticket.builder()
                .id(id).booking(booking).passenger(passenger)
                .ticketNumber("125000000070" + id).status(TicketStatus.ISSUED)
                .issuedAt(LocalDateTime.now()).build();
        int couponNumber = 1;
        for (BookingPassenger row : rows) {
            ticket.getCoupons().add(TicketCoupon.builder().ticket(ticket).bookingPassenger(row)
                    .couponNumber(couponNumber++).status(CouponStatus.OPEN).build());
        }
        booking.getTickets().add(ticket);
        return ticket;
    }

    private long bookingStatusChanges(Booking booking) {
        return booking.getHistory().stream()
                .filter(entry -> entry.getFieldChanged() == BookingHistoryField.BOOKING_STATUS)
                .count();
    }

    /** Two adults on one flight, each ticketed with a single coupon. */
    private Booking twoAdultsOneWay(FareType fareType, PaymentStatus paymentStatus) {
        Booking booking = booking(BookingStatus.CONFIRMED, paymentStatus);
        BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
        Passenger ada = traveller(41L, "Ada", 35);
        Passenger ben = traveller(42L, "Ben", 30);
        ticket(booking, 1L, ada, row(booking, 100L, ada, outbound, fareType, "100.00", CheckInStatus.NOT_OPEN));
        ticket(booking, 2L, ben, row(booking, 101L, ben, outbound, fareType, "100.00", CheckInStatus.NOT_OPEN));
        return booking;
    }

    // ---------------------------------------------------------------
    // The booking survives (rule 9)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Cancelling some passengers reprices the booking that survives")
    class BookingSurvives {

        @Test
        void theCancelledPassengerLosesTheirSeatAndTheRestKeepEverything() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PAID);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            CancelPassengersResponse result = bookingService.cancelPassengers(7L, List.of(100L), 100);

            assertThat(result.bookingCancelled()).isFalse();
            assertThat(result.refundAmount()).isEqualByComparingTo("100.00");
            assertThat(result.cancelledRowIds()).containsExactly(100L);
            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.PARTIALLY_CANCELLED);
            // The booking is now worth what remains, and the payment mirror
            // tracks what the capture still covers.
            assertThat(booking.getTotalFare()).isEqualByComparingTo("100.00");
            assertThat(booking.getPayment().getAmount()).isEqualByComparingTo("100.00");
            assertThat(booking.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(booking.getPassengers().get(0).isCancelled()).isTrue();
            assertThat(booking.getPassengers().get(0).getCheckInStatus()).isEqualTo(CheckInStatus.CLOSED);
            // Rule 7: the remaining traveller's check-in is untouched.
            assertThat(booking.getPassengers().get(1).isCancelled()).isFalse();
            assertThat(booking.getPassengers().get(1).getCheckInStatus()).isEqualTo(CheckInStatus.NOT_OPEN);
            // Their ticket has no live coupon left, so the ticket is refunded too.
            assertThat(booking.getTickets().get(0).getCoupons().get(0).getStatus())
                    .isEqualTo(CouponStatus.REFUNDED);
            assertThat(booking.getTickets().get(0).getStatus()).isEqualTo(TicketStatus.REFUNDED);
            assertThat(booking.getTickets().get(1).getCoupons().get(0).getStatus())
                    .isEqualTo(CouponStatus.OPEN);
            assertThat(booking.getTickets().get(1).getStatus()).isEqualTo(TicketStatus.ISSUED);
        }

        @Test
        void aSaverForfeitsItsFareRuleFeeBeforeTheTimeTierApplies() {
            Booking booking = twoAdultsOneWay(FareType.SAVER, PaymentStatus.PAID);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            CancelPassengersResponse result = bookingService.cancelPassengers(7L, List.of(100L), 100);

            // Fare rules first (Saver keeps 30%), then the full-refund tier
            // scales what survived: 100 - 30 = 70.
            assertThat(result.refundAmount()).isEqualByComparingTo("70.00");
        }

        @Test
        void theZeroRefundTierClosesCouponsAsCancelledRatherThanRefunded() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PAID);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            CancelPassengersResponse result = bookingService.cancelPassengers(7L, List.of(100L), 0);

            // A "refunded" coupon with no money behind it would be a lie.
            assertThat(result.refundAmount()).isEqualByComparingTo("0.00");
            assertThat(booking.getTickets().get(0).getCoupons().get(0).getStatus())
                    .isEqualTo(CouponStatus.CANCELLED);
            assertThat(booking.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        void aFlownCouponIsHistoryAndSurvivesTheCancellation() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PAID);
            booking.getTickets().get(0).getCoupons().get(0).setStatus(CouponStatus.FLOWN);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            bookingService.cancelPassengers(7L, List.of(100L), 100);

            assertThat(booking.getTickets().get(0).getCoupons().get(0).getStatus())
                    .isEqualTo(CouponStatus.FLOWN);
            // No live coupon remains on that ticket either way.
            assertThat(booking.getTickets().get(0).getStatus()).isEqualTo(TicketStatus.REFUNDED);
        }

        @Test
        void aSecondPartialCancelRepricesWithoutRecordingAnotherStatusChange() {
            Booking booking = booking(BookingStatus.PARTIALLY_CANCELLED, PaymentStatus.PAID);
            BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
            Passenger ada = traveller(41L, "Ada", 35);
            Passenger ben = traveller(42L, "Ben", 30);
            Passenger cara = traveller(43L, "Cara", 28);
            row(booking, 100L, ada, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN)
                    .setCancelled(true);
            row(booking, 101L, ben, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            row(booking, 102L, cara, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            bookingService.cancelPassengers(7L, List.of(101L), 100);

            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.PARTIALLY_CANCELLED);
            assertThat(booking.getTotalFare()).isEqualByComparingTo("100.00");
            // Already partially cancelled - no redundant transition recorded.
            assertThat(bookingStatusChanges(booking)).isZero();
        }
    }

    // ---------------------------------------------------------------
    // The last passenger goes (rule 11)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Cancelling the last passenger cancels the booking itself")
    class BookingEmptied {

        @Test
        void everyPassengerCancelledRefundsThePaymentAndClosesEveryTicket() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PAID);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            CancelPassengersResponse result = bookingService.cancelPassengers(7L, List.of(100L, 101L), 100);

            assertThat(result.bookingCancelled()).isTrue();
            assertThat(result.refundAmount()).isEqualByComparingTo("200.00");
            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(booking.getTickets()).allMatch(t -> t.getStatus() == TicketStatus.REFUNDED);
        }

        @Test
        void aForfeitedFareLeavesThePaymentPaidWithNothingToReturn() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PAID);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            bookingService.cancelPassengers(7L, List.of(100L, 101L), 0);

            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        void anUnpaidBookingIsSimplyCancelled() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PENDING);
            booking.setBookingStatus(BookingStatus.CREATED);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            bookingService.cancelPassengers(7L, List.of(100L, 101L), 100);

            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }

    // ---------------------------------------------------------------
    // Who may be cancelled at all
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Guards on who may be cancelled")
    class Guards {

        @Test
        void aMinorCannotBeLeftOnTheBookingWithoutAnAdult() {
            Booking booking = booking(BookingStatus.CONFIRMED, PaymentStatus.PAID);
            BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
            Passenger guardian = traveller(41L, "Ada", 35);
            Passenger child = traveller(42L, "Kit", 6);
            row(booking, 100L, guardian, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            row(booking, 101L, child, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelPassengers(7L, List.of(100L), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without an adult");

            assertThat(booking.getPassengers()).noneMatch(BookingPassenger::isCancelled);
        }

        @Test
        void aMinorMayGoAsLongAsTheAdultStays() {
            Booking booking = booking(BookingStatus.CONFIRMED, PaymentStatus.PAID);
            BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
            Passenger guardian = traveller(41L, "Ada", 35);
            Passenger child = traveller(42L, "Kit", 6);
            row(booking, 100L, guardian, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            row(booking, 101L, child, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            CancelPassengersResponse result = bookingService.cancelPassengers(7L, List.of(101L), 100);

            assertThat(result.bookingCancelled()).isFalse();
            assertThat(booking.getPassengers().get(1).isCancelled()).isTrue();
        }

        @Test
        void aCheckedInTravellerCannotBeCancelledOnTheirOwn() {
            Booking booking = booking(BookingStatus.CONFIRMED, PaymentStatus.PAID);
            BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
            Passenger ada = traveller(41L, "Ada", 35);
            Passenger ben = traveller(42L, "Ben", 30);
            row(booking, 100L, ada, outbound, FareType.FLEXI, "100.00", CheckInStatus.CHECKED_IN);
            row(booking, 101L, ben, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            // No event exists to void just their boarding pass - cancelling
            // the whole booking (which voids every pass) still works.
            assertThatThrownBy(() -> bookingService.cancelPassengers(7L, List.of(100L), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ada has already checked in");
        }

        @Test
        void aBoardedTravellerIsBeyondCancellationToo() {
            Booking booking = booking(BookingStatus.CONFIRMED, PaymentStatus.PAID);
            BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
            Passenger ada = traveller(41L, "Ada", 35);
            row(booking, 100L, ada, outbound, FareType.FLEXI, "100.00", CheckInStatus.BOARDED);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelPassengers(7L, List.of(100L), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be cancelled individually");
        }

        @Test
        void rowsThatAreNotOnThisBookingAreRejected() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PAID);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelPassengers(7L, List.of(999L), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("None of the selected passengers");
        }

        @Test
        void anAlreadyCancelledPassengerCannotBeCancelledTwice() {
            Booking booking = twoAdultsOneWay(FareType.FLEXI, PaymentStatus.PAID);
            booking.getPassengers().get(0).setCancelled(true);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelPassengers(7L, List.of(100L), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already cancelled");
        }
    }

    // ---------------------------------------------------------------
    // Round trip: a traveller is cancelled off every leg (§7)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("A cancelled traveller comes off every segment")
    class AcrossSegments {

        @Test
        void selectingOneLegsRowCancelsThatTravellersOtherLegsToo() {
            // You cannot fly out and not exist on the return, so selecting any
            // one of a traveller's per-segment rows expands to all of them.
            Booking booking = booking(BookingStatus.CONFIRMED, PaymentStatus.PAID);
            BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
            BookingSegment inbound = segment(booking, 2L, 1, 1, 20L);
            Passenger ada = traveller(41L, "Ada", 35);
            Passenger ben = traveller(42L, "Ben", 30);
            BookingPassenger adaOut = row(booking, 100L, ada, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            BookingPassenger adaBack = row(booking, 101L, ada, inbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            BookingPassenger benOut = row(booking, 200L, ben, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            BookingPassenger benBack = row(booking, 201L, ben, inbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            ticket(booking, 1L, ada, adaOut, adaBack);
            ticket(booking, 2L, ben, benOut, benBack);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            CancelPassengersResponse result = bookingService.cancelPassengers(7L, List.of(100L), 100);

            assertThat(result.cancelledRowIds()).containsExactlyInAnyOrder(100L, 101L);
            assertThat(adaOut.isCancelled()).isTrue();
            assertThat(adaBack.isCancelled()).isTrue();
            // Refund covers both of their legs, not just the row that was clicked.
            assertThat(result.refundAmount()).isEqualByComparingTo("200.00");
            // The other traveller keeps both of theirs.
            assertThat(benOut.isCancelled()).isFalse();
            assertThat(benBack.isCancelled()).isFalse();
            // Ada's ticket is fully refunded; Ben's still has two live coupons.
            assertThat(booking.getTickets().get(0).getStatus()).isEqualTo(TicketStatus.REFUNDED);
            assertThat(booking.getTickets().get(1).getStatus()).isEqualTo(TicketStatus.ISSUED);
            assertThat(booking.getTickets().get(1).getCoupons())
                    .allMatch(coupon -> coupon.getStatus() == CouponStatus.OPEN);

            // What is left to fly is Ben's two legs and nothing else. Ada's
            // return row must not be counted as remaining just because the
            // caller happened to name her outbound one.
            assertThat(booking.getTotalFare()).isEqualByComparingTo("200.00");
            assertThat(booking.getPayment().getAmount()).isEqualByComparingTo("200.00");
        }

        @Test
        void cancellingTheOnlyTravellerOnARoundTripCancelsTheWholeBooking() {
            // The failure this guards against is silent and expensive: the
            // selection expands to both legs, so nothing is left to fly - but
            // if "remaining" is derived from the caller's raw ids, the return
            // row still counts as remaining. The booking then settles as
            // PARTIALLY_CANCELLED carrying a non-zero balance, so the
            // remainder is never refunded and check-ins are never closed.
            Booking booking = booking(BookingStatus.CONFIRMED, PaymentStatus.PAID);
            BookingSegment outbound = segment(booking, 1L, 0, 0, 10L);
            BookingSegment inbound = segment(booking, 2L, 1, 1, 20L);
            Passenger ada = traveller(41L, "Ada", 35);
            BookingPassenger adaOut = row(booking, 100L, ada, outbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            BookingPassenger adaBack = row(booking, 101L, ada, inbound, FareType.FLEXI, "100.00", CheckInStatus.NOT_OPEN);
            ticket(booking, 1L, ada, adaOut, adaBack);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            CancelPassengersResponse result = bookingService.cancelPassengers(7L, List.of(100L), 100);

            assertThat(result.bookingCancelled()).isTrue();
            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(result.refundAmount()).isEqualByComparingTo("200.00");
            // Reaching REFUNDED is the point: stranded in PARTIALLY_CANCELLED
            // the payment stays PAID and the money is never returned. The fare
            // itself is left standing as the historical record, the same as
            // every other whole-cancel path - the ledger lives in
            // payment-service.
            assertThat(booking.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(booking.getTickets()).allMatch(t -> t.getStatus() == TicketStatus.REFUNDED);
        }
    }
}
