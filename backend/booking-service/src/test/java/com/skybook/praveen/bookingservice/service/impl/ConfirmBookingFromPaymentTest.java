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
