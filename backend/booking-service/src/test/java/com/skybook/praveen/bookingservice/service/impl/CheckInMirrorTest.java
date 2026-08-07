package com.skybook.praveen.bookingservice.service.impl;

import com.skybook.praveen.bookingservice.domain.BookingStateMachine;
import com.skybook.praveen.bookingservice.domain.BookingValidator;
import com.skybook.praveen.bookingservice.domain.CancellationPolicy;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.PnrGenerator;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.entity.BookingSegment;
import com.skybook.praveen.bookingservice.entity.Passenger;
import com.skybook.praveen.bookingservice.entity.Ticket;
import com.skybook.praveen.bookingservice.entity.TicketCoupon;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TicketStatus;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.BookingPassengerNotFoundException;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read-model writes driven from outside this service: checkin-service's
 * authoritative per-passenger state (consumed from CheckInEvent) and the seat
 * numbers the facade writes back once inventory has secured them.
 *
 * <p>The mirror is deliberately forgiving - replays, out-of-order deliveries
 * and a window that was never announced are all normal for a read model, and
 * none of them may poison the topic.
 */
@ExtendWith(MockitoExtension.class)
class CheckInMirrorTest {

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

    private Booking booking() {
        Booking booking = Booking.builder()
                .id(7L).bookingReference("SBMIRR").customerId(1L).flightId(10L)
                .bookingStatus(BookingStatus.CONFIRMED).bookingDate(LocalDateTime.now())
                .totalFare(new BigDecimal("100.00"))
                .build();
        booking.setPassengers(new ArrayList<>());
        booking.setHistory(new ArrayList<>());
        booking.setPayment(BookingPayment.builder().id(3L).booking(booking)
                .paymentStatus(PaymentStatus.PAID).amount(new BigDecimal("100.00")).currency("GBP").build());
        booking.getSegments().add(BookingSegment.builder()
                .id(1L).booking(booking).segmentIndex(0).direction(0).flightId(10L).build());
        return booking;
    }

    private BookingPassenger row(Booking booking, long id, String seat, CheckInStatus checkInStatus) {
        BookingPassenger row = BookingPassenger.builder()
                .id(id).booking(booking)
                .passenger(Passenger.builder().id(40L + id).firstName("Pax").lastName("Test")
                        .dob(LocalDate.now().minusYears(35)).build())
                .segment(booking.getSegments().get(0)).flightId(10L)
                .travelClass(TravelClass.ECONOMY).fareType(FareType.FLEXI).seatNumber(seat)
                .baseFare(new BigDecimal("100.00")).seatSurcharge(BigDecimal.ZERO)
                .extraBags(0).baggageFee(BigDecimal.ZERO)
                .chargedSeatAssignmentMode(SeatAssignmentMode.AUTO).currency("GBP")
                .fare(new BigDecimal("100.00")).checkInStatus(checkInStatus).cancelled(false)
                .build();
        booking.getPassengers().add(row);
        return row;
    }

    private Ticket ticketFor(Booking booking, BookingPassenger row) {
        Ticket ticket = Ticket.builder()
                .id(1L).booking(booking).passenger(row.getPassenger())
                .ticketNumber("1250000000701").status(TicketStatus.ISSUED)
                .issuedAt(LocalDateTime.now()).build();
        ticket.getCoupons().add(TicketCoupon.builder().id(51L).ticket(ticket).bookingPassenger(row)
                .couponNumber(1).status(CouponStatus.OPEN).build());
        booking.getTickets().add(ticket);
        return ticket;
    }

    private void stubLookup(Booking booking, BookingPassenger row) {
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(bookingPassengerRepository.findByIdAndBooking_Id(row.getId(), 7L)).thenReturn(Optional.of(row));
    }

    // ---------------------------------------------------------------
    // applyCheckInStatus - the CheckInEvent mirror
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("The check-in mirror follows checkin-service without ever fighting it")
    class Mirror {

        @Test
        void aCheckInStepsThroughOpenBecauseNobodyAnnouncesTheWindow() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
            Ticket ticket = ticketFor(booking, row);
            stubLookup(booking, row);

            bookingService.applyCheckInStatus(7L, 100L, CheckInStatus.CHECKED_IN, "12A");

            assertThat(row.getCheckInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
            // Both hops are recorded, so the history reads coherently even
            // though only the terminal fact was ever published.
            assertThat(booking.getHistory()).hasSize(2);
            assertThat(booking.getHistory().get(0).getToValue()).isEqualTo(CheckInStatus.OPEN.name());
            assertThat(booking.getHistory().get(1).getToValue()).isEqualTo(CheckInStatus.CHECKED_IN.name());
            // The coupon follows the row.
            assertThat(ticket.getCoupons().get(0).getStatus()).isEqualTo(CouponStatus.CHECKED_IN);
        }

        @Test
        void aNoShowFromAWindowThatNeverOpenedStepsThroughOpenToo() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
            stubLookup(booking, row);

            bookingService.applyCheckInStatus(7L, 100L, CheckInStatus.NO_SHOW, null);

            assertThat(row.getCheckInStatus()).isEqualTo(CheckInStatus.NO_SHOW);
        }

        @Test
        void boardingIsMirroredWithoutTouchingTheCoupon() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.CHECKED_IN);
            Ticket ticket = ticketFor(booking, row);
            ticket.getCoupons().get(0).setStatus(CouponStatus.CHECKED_IN);
            stubLookup(booking, row);

            bookingService.applyCheckInStatus(7L, 100L, CheckInStatus.BOARDED, "12A");

            assertThat(row.getCheckInStatus()).isEqualTo(CheckInStatus.BOARDED);
            assertThat(ticket.getCoupons().get(0).getStatus()).isEqualTo(CouponStatus.CHECKED_IN);
        }

        @Test
        void aSeatChangeMadeAtCheckInIsMirroredOntoTheBookedRow() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
            stubLookup(booking, row);

            bookingService.applyCheckInStatus(7L, 100L, CheckInStatus.CHECKED_IN, "14C");

            // Without this the mirror would keep the booked seat and a later
            // cancel would release the wrong one, leaking the seat they hold.
            assertThat(row.getSeatNumber()).isEqualTo("14C");
            assertThat(row.getCheckInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
        }

        @Test
        void aRedeliveredEventIsANoOpRatherThanAnError() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.CHECKED_IN);
            stubLookup(booking, row);

            bookingService.applyCheckInStatus(7L, 100L, CheckInStatus.CHECKED_IN, "12A");

            assertThat(row.getCheckInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
            assertThat(booking.getHistory()).isEmpty();
            verify(bookingRepository, never()).save(any());
        }

        @Test
        void aSeatMoveIsStillPersistedWhenTheStatusItselfIsAReplay() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.CHECKED_IN);
            stubLookup(booking, row);

            bookingService.applyCheckInStatus(7L, 100L, CheckInStatus.CHECKED_IN, "14C");

            assertThat(row.getSeatNumber()).isEqualTo("14C");
            assertThat(booking.getHistory()).isEmpty();
            verify(bookingRepository, times(1)).save(booking);
        }

        @Test
        void aLateEventCannotUnwindAMoreAdvancedState() {
            // The passenger was cancelled and CLOSED; a CHECKED_IN event that
            // was in flight at the time must be dropped, not applied.
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.CLOSED);
            stubLookup(booking, row);

            bookingService.applyCheckInStatus(7L, 100L, CheckInStatus.CHECKED_IN, "12A");

            assertThat(row.getCheckInStatus()).isEqualTo(CheckInStatus.CLOSED);
            assertThat(booking.getHistory()).isEmpty();
            verify(bookingRepository, never()).save(any());
        }
    }

    // ---------------------------------------------------------------
    // Seat writes the facade drives after inventory has spoken
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Seat numbers are written only after inventory secured them")
    class SeatWrites {

        @Test
        void aSeatChangeIsRecordedAsAManualPick() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
            stubLookup(booking, row);

            BookingResponse response = bookingService.updateSeatNumber(7L, 100L, "14C");

            assertThat(response.passengers().get(0).seatNumber()).isEqualTo("14C");
            // A chosen seat is MANUAL whatever the row started as - the stored
            // surcharge is deliberately left alone.
            assertThat(row.getChargedSeatAssignmentMode()).isEqualTo(SeatAssignmentMode.MANUAL);
            assertThat(row.getSeatSurcharge()).isEqualByComparingTo("0.00");
        }

        @Test
        void applySeatNumbersWritesOnlyTheRowsItWasGiven() {
            Booking booking = booking();
            BookingPassenger seated = row(booking, 100L, null, CheckInStatus.NOT_OPEN);
            BookingPassenger untouched = row(booking, 101L, "9F", CheckInStatus.NOT_OPEN);
            BookingPassenger blank = row(booking, 102L, null, CheckInStatus.NOT_OPEN);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            Map<Long, String> seats = new HashMap<>();
            seats.put(100L, "21F");
            seats.put(102L, "   ");
            BookingResponse response = bookingService.applySeatNumbers(7L, seats);

            assertThat(seated.getSeatNumber()).isEqualTo("21F");
            assertThat(untouched.getSeatNumber()).isEqualTo("9F");
            // A blank answer from inventory means "no seat", not "clear it".
            assertThat(blank.getSeatNumber()).isNull();
            assertThat(response.passengers()).hasSize(3);
        }

        @Test
        void applySeatNumbersWithNothingToApplyLeavesTheBookingAlone() {
            Booking booking = booking();
            BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
            when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

            bookingService.applySeatNumbers(7L, Map.of());

            assertThat(row.getSeatNumber()).isEqualTo("12A");
        }
    }

    // ---------------------------------------------------------------
    // checkInPassenger's own coupon mirror
    // ---------------------------------------------------------------

    @Test
    void checkingInDirectlyAlsoMovesTheTravellersCoupon() {
        Booking booking = booking();
        BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
        Ticket ticket = ticketFor(booking, row);
        stubLookup(booking, row);

        bookingService.checkInPassenger(7L, 100L);

        assertThat(row.getCheckInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
        assertThat(ticket.getCoupons().get(0).getStatus()).isEqualTo(CouponStatus.CHECKED_IN);
    }

    @Test
    void onlyAnOpenCouponFollowsTheRowToCheckedIn() {
        // A coupon that was already exchanged away (CANCELLED) must not be
        // resurrected by a check-in on the row it used to cover.
        Booking booking = booking();
        BookingPassenger row = row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
        Ticket ticket = ticketFor(booking, row);
        ticket.getCoupons().get(0).setStatus(CouponStatus.CANCELLED);
        stubLookup(booking, row);

        bookingService.checkInPassenger(7L, 100L);

        assertThat(ticket.getCoupons().get(0).getStatus()).isEqualTo(CouponStatus.CANCELLED);
    }

    @Test
    void aMirroredEventForARowOnAnotherBookingIsRejected() {
        Booking booking = booking();
        row(booking, 100L, "12A", CheckInStatus.NOT_OPEN);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(bookingPassengerRepository.findByIdAndBooking_Id(999L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.applyCheckInStatus(7L, 999L, CheckInStatus.CHECKED_IN, "12A"))
                .isInstanceOf(BookingPassengerNotFoundException.class);
    }
}
