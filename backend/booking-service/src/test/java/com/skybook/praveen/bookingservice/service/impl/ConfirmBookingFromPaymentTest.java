package com.skybook.praveen.bookingservice.service.impl;

import com.skybook.praveen.bookingservice.domain.BookingStateMachine;
import com.skybook.praveen.bookingservice.domain.BookingValidator;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.PnrGenerator;
import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.repository.BookingPassengerRepository;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import com.skybook.praveen.bookingservice.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 6's event-driven confirmation, with the REAL state machine - the
 * payment reference recording and the idempotent replay are what
 * PaymentEventConsumer's correctness rests on.
 */
@ExtendWith(MockitoExtension.class)
class ConfirmBookingFromPaymentTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingPassengerRepository bookingPassengerRepository;
    @Mock
    private PnrGenerator pnrGenerator;
    @Mock
    private BookingValidator bookingValidator;
    @Mock
    private FareCalculator fareCalculator;

    private BookingServiceImpl bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingServiceImpl(
                bookingRepository, bookingPassengerRepository,
                pnrGenerator, new BookingStateMachine(), bookingValidator,
                fareCalculator, 15);
    }

    private Booking bookingWith(BookingStatus status, PaymentStatus paymentStatus) {
        Booking booking = Booking.builder()
                .id(7L)
                .bookingReference("SBCONF")
                .customerId(1L)
                .flightId(10L)
                .bookingStatus(status)
                .bookingDate(LocalDateTime.now())
                .totalFare(new BigDecimal("100.00"))
                .build();
        booking.setPassengers(new ArrayList<>());
        booking.setHistory(new ArrayList<>());

        BookingPayment payment = BookingPayment.builder()
                .id(3L)
                .booking(booking)
                .paymentStatus(paymentStatus)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();
        booking.setPayment(payment);
        return booking;
    }

    @Test
    void confirmsWithTheRealPaymentReference() {
        Booking booking = bookingWith(BookingStatus.CREATED, PaymentStatus.PENDING);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingService.PaymentConfirmation result =
                bookingService.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9");

        assertThat(result.transitioned()).isTrue();
        assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(booking.getPayment().getExternalPaymentReference()).isEqualTo("PAY-2026-K7M4Z9");
        assertThat(booking.getHistory()).isNotEmpty();
    }

    @Test
    void confirmationIssuesOneTicketPerTravellerWithACouponPerSegment() {
        // Round-trip shape: one traveller, two per-segment rows sharing one
        // Passenger identity - ONE ticket, coupons 1 (outbound) + 2 (return).
        Booking booking = bookingWith(BookingStatus.CREATED, PaymentStatus.PENDING);
        com.skybook.praveen.bookingservice.entity.Passenger traveller =
                com.skybook.praveen.bookingservice.entity.Passenger.builder()
                        .id(42L).firstName("Pax").lastName("Test")
                        .dob(java.time.LocalDate.of(1990, 1, 1)).build();
        com.skybook.praveen.bookingservice.entity.BookingSegment outbound =
                com.skybook.praveen.bookingservice.entity.BookingSegment.builder()
                        .id(1L).booking(booking).segmentIndex(0).flightId(10L).build();
        com.skybook.praveen.bookingservice.entity.BookingSegment inbound =
                com.skybook.praveen.bookingservice.entity.BookingSegment.builder()
                        .id(2L).booking(booking).segmentIndex(1).flightId(20L).build();
        booking.getSegments().addAll(java.util.List.of(outbound, inbound));
        booking.getPassengers().addAll(java.util.List.of(
                com.skybook.praveen.bookingservice.entity.BookingPassenger.builder()
                        .id(100L).booking(booking).passenger(traveller).segment(outbound)
                        .flightId(10L).fare(new BigDecimal("100.00")).build(),
                com.skybook.praveen.bookingservice.entity.BookingPassenger.builder()
                        .id(101L).booking(booking).passenger(traveller).segment(inbound)
                        .flightId(20L).fare(new BigDecimal("100.00")).build()));
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        bookingService.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9");

        assertThat(booking.getTickets()).hasSize(1);
        com.skybook.praveen.bookingservice.entity.Ticket ticket = booking.getTickets().get(0);
        assertThat(ticket.getTicketNumber()).isEqualTo("1250000000701");
        assertThat(ticket.getStatus())
                .isEqualTo(com.skybook.praveen.bookingservice.enums.TicketStatus.ISSUED);
        assertThat(ticket.getCoupons()).hasSize(2);
        assertThat(ticket.getCoupons().get(0).getCouponNumber()).isEqualTo(1);
        assertThat(ticket.getCoupons().get(0).getBookingPassenger().getId()).isEqualTo(100L);
        assertThat(ticket.getCoupons().get(1).getCouponNumber()).isEqualTo(2);
        assertThat(ticket.getCoupons()).allMatch(c ->
                c.getStatus() == com.skybook.praveen.bookingservice.enums.CouponStatus.OPEN);

        // Redelivered event: idempotent, no second ticket.
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        bookingService.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9");
        assertThat(booking.getTickets()).hasSize(1);
    }

    @Test
    void duplicatePaymentEventIsAnIdempotentNoOp() {
        Booking booking = bookingWith(BookingStatus.CONFIRMED, PaymentStatus.PAID);
        booking.getPayment().setExternalPaymentReference("PAY-2026-K7M4Z9");
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

        BookingService.PaymentConfirmation result =
                bookingService.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9");

        assertThat(result.transitioned()).isFalse();
        assertThat(booking.getHistory()).isEmpty();
        verify(bookingRepository, never()).save(any());
    }
}
