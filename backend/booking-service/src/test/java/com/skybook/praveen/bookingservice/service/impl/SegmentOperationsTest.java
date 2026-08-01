package com.skybook.praveen.bookingservice.service.impl;

import com.skybook.praveen.bookingservice.domain.BookingStateMachine;
import com.skybook.praveen.bookingservice.domain.BookingValidator;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.PnrGenerator;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;
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
import com.skybook.praveen.bookingservice.repository.BookingPassengerRepository;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * The cancellation matrix's segment operations (ROUND_TRIP_MODULE.md §7/§11):
 * drop-the-return, the return-only guard, and the Premium-only date change
 * with coupon exchange - all with the REAL state machine and fare calculator.
 */
@ExtendWith(MockitoExtension.class)
class SegmentOperationsTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingPassengerRepository bookingPassengerRepository;
    @Mock
    private PnrGenerator pnrGenerator;
    @Mock
    private BookingValidator bookingValidator;

    private BookingServiceImpl bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingServiceImpl(
                bookingRepository, bookingPassengerRepository,
                pnrGenerator, new BookingStateMachine(), bookingValidator,
                new FareCalculator(Clock.fixed(Instant.parse("2030-06-04T09:00:00Z"), ZoneOffset.UTC)),
                new com.skybook.praveen.bookingservice.domain.CancellationPolicy(new java.math.BigDecimal("30"), 72, 24, 2), 15);
        lenient().when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** CONFIRMED round trip: one FLEXI traveller (or PREMIUM), two segments, ticket with two OPEN coupons. */
    private Booking roundTrip(FareType fareType) {
        Booking booking = Booking.builder()
                .id(7L).bookingReference("SBSEGM").customerId(1L).flightId(10L)
                .bookingStatus(BookingStatus.CONFIRMED)
                .bookingDate(LocalDateTime.now())
                .totalFare(new BigDecimal("200.00"))
                .build();
        booking.setPassengers(new ArrayList<>());
        booking.setHistory(new ArrayList<>());
        booking.setPayment(BookingPayment.builder().id(3L).booking(booking)
                .paymentStatus(PaymentStatus.PAID).amount(new BigDecimal("200.00")).currency("USD").build());

        Passenger traveller = Passenger.builder().id(42L).firstName("Pax").lastName("Test")
                .dob(LocalDate.of(1990, 1, 1)).build();
        BookingSegment outbound = BookingSegment.builder().id(1L).booking(booking).segmentIndex(0).direction(0).flightId(10L).build();
        BookingSegment inbound = BookingSegment.builder().id(2L).booking(booking).segmentIndex(1).direction(1).flightId(20L).build();
        booking.getSegments().addAll(List.of(outbound, inbound));

        BookingPassenger outRow = row(100L, booking, traveller, outbound, 10L, fareType, "12A");
        BookingPassenger inRow = row(101L, booking, traveller, inbound, 20L, fareType, "14C");
        booking.getPassengers().addAll(List.of(outRow, inRow));

        Ticket ticket = Ticket.builder().id(5L).booking(booking).passenger(traveller)
                .ticketNumber("1250000000701").status(TicketStatus.ISSUED).issuedAt(LocalDateTime.now()).build();
        ticket.getCoupons().add(TicketCoupon.builder().id(51L).ticket(ticket).bookingPassenger(outRow)
                .couponNumber(1).status(CouponStatus.OPEN).build());
        ticket.getCoupons().add(TicketCoupon.builder().id(52L).ticket(ticket).bookingPassenger(inRow)
                .couponNumber(2).status(CouponStatus.OPEN).build());
        booking.getTickets().add(ticket);
        return booking;
    }

    private BookingPassenger row(long id, Booking booking, Passenger traveller, BookingSegment segment,
                                 Long flightId, FareType fareType, String seat) {
        return BookingPassenger.builder()
                .id(id).booking(booking).passenger(traveller).segment(segment).flightId(flightId)
                .travelClass(TravelClass.ECONOMY).fareType(fareType).seatNumber(seat)
                .baseFare(new BigDecimal("100.00")).seatSurcharge(BigDecimal.ZERO)
                .extraBags(0).baggageFee(BigDecimal.ZERO)
                .chargedSeatAssignmentMode(SeatAssignmentMode.AUTO).currency("USD")
                .fare(new BigDecimal("100.00")).checkInStatus(CheckInStatus.NOT_OPEN)
                .cancelled(false).build();
    }

    @Test
    void cancellingTheReturnRefundsItsCouponAndDerivesPartiallyCancelled() {
        Booking booking = roundTrip(FareType.FLEXI);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

        CancelPassengersResponse result = bookingService.cancelSegment(7L, 1, 100);

        assertThat(result.refundAmount()).isEqualByComparingTo("100.00");
        assertThat(result.bookingCancelled()).isFalse();
        assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.PARTIALLY_CANCELLED);
        assertThat(booking.getTotalFare()).isEqualByComparingTo("100.00");
        // Return row cancelled, outbound untouched; coupon 2 REFUNDED, 1 OPEN.
        assertThat(booking.getPassengers().get(1).isCancelled()).isTrue();
        assertThat(booking.getPassengers().get(0).isCancelled()).isFalse();
        List<TicketCoupon> coupons = booking.getTickets().get(0).getCoupons();
        assertThat(coupons.get(0).getStatus()).isEqualTo(CouponStatus.OPEN);
        assertThat(coupons.get(1).getStatus()).isEqualTo(CouponStatus.REFUNDED);
        // The ticket itself survives - one live coupon remains.
        assertThat(booking.getTickets().get(0).getStatus()).isEqualTo(TicketStatus.ISSUED);
    }

    @Test
    void theOutboundCanNeverBeCancelledAlone() {
        Booking booking = roundTrip(FareType.FLEXI);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelSegment(7L, 0, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("on its own");
    }

    @Test
    void premiumDateChangeExchangesCouponsAndAdjustsTheFare() {
        Booking booking = roundTrip(FareType.PREMIUM);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

        // New departure in the neutral 1.00 band; PREMIUM ECONOMY base = 100 x 1.60 = 160.00.
        BookingResponse result = bookingService.rebookSegment(7L, 1, 30L,
                LocalDateTime.of(2030, 7, 23, 10, 0));

        // Old return row exchanged away, replacement row live on flight 30.
        assertThat(booking.getPassengers()).hasSize(3);
        BookingPassenger replacement = booking.getPassengers().get(2);
        assertThat(replacement.getFlightId()).isEqualTo(30L);
        assertThat(replacement.getSeatNumber()).isNull();
        assertThat(booking.getPassengers().get(1).isCancelled()).isTrue();
        // Coupon 2 CANCELLED (exchanged, not refunded), coupon 3 OPEN.
        List<TicketCoupon> coupons = booking.getTickets().get(0).getCoupons();
        assertThat(coupons.get(1).getStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(coupons.get(2).getCouponNumber()).isEqualTo(3);
        assertThat(coupons.get(2).getStatus()).isEqualTo(CouponStatus.OPEN);
        // Payment snapshot follows the new total; history records the change.
        assertThat(booking.getPayment().getAmount()).isEqualByComparingTo(booking.getTotalFare());
        assertThat(booking.getHistory()).anyMatch(h -> h.getReason() != null
                && h.getReason().contains("Premium date change"));
        assertThat(result.segments().get(1).flightId()).isEqualTo(30L);
    }

    @Test
    void onlyPremiumMayChangeDatesOnline() {
        Booking booking = roundTrip(FareType.FLEXI);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.rebookSegment(7L, 1, 30L,
                LocalDateTime.of(2030, 7, 23, 10, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Premium");
    }
}
