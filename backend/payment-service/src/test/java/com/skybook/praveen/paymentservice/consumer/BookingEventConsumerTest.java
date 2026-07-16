package com.skybook.praveen.paymentservice.consumer;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventType;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.exception.PaymentNotFoundException;
import com.skybook.praveen.paymentservice.facade.PaymentFacade;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingEventConsumerTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private PaymentFacade paymentFacade;

    private BookingEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BookingEventConsumer(paymentService, paymentFacade);
    }

    private BookingEvent event(BookingEventType type) {
        return BookingEvent.builder()
                .type(type)
                .bookingId(42L)
                .bookingReference("SBTEST")
                .totalFare(new BigDecimal("100.00"))
                .currency("USD")
                .build();
    }

    private PaymentResponse payment(PaymentStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new PaymentResponse(1L, "PAY-2026-TESTAA", 42L, "SBTEST",
                new BigDecimal("100.00"), "USD", BigDecimal.ZERO, BigDecimal.ZERO,
                status, PaymentMethod.CARD, null, null, null, null, List.of(), List.of(), 0L, now, now);
    }

    @Test
    void createdEventCreatesThePayment() {
        consumer.consume(event(BookingEventType.CREATED));

        verify(paymentService).createFromBookingEvent(any(BookingEvent.class));
        verifyNoInteractions(paymentFacade);
    }

    @Test
    void eventWithoutBookingIdIsSkippedEntirely() {
        BookingEvent lean = event(BookingEventType.CREATED);
        lean.setBookingId(null);

        consumer.consume(lean);

        verifyNoInteractions(paymentService, paymentFacade);
    }

    @Test
    void confirmedAndOtherEventsAreIgnored() {
        consumer.consume(event(BookingEventType.CONFIRMED));
        consumer.consume(event(BookingEventType.COMPLETED));

        verifyNoInteractions(paymentService, paymentFacade);
    }

    @Test
    void cancelledBookingWithCapturedPaymentTriggersAFullRefund() {
        when(paymentService.getByBookingId(42L)).thenReturn(payment(PaymentStatus.CAPTURED));

        consumer.consume(event(BookingEventType.CANCELLED));

        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        ArgumentCaptor<ActionContext> ctxCaptor = ArgumentCaptor.forClass(ActionContext.class);
        verify(paymentFacade).refund(eq(1L), captor.capture(), ctxCaptor.capture());
        assertThat(captor.getValue().fareLines()).isNull(); // full refund per stored breakdown
        assertThat(captor.getValue().reason()).contains("SBTEST");
        assertThat(ctxCaptor.getValue().actor()).isEqualTo("KAFKA");
    }

    @Test
    void cancelledBookingWithUncapturedPaymentCancelsIt() {
        when(paymentService.getByBookingId(42L)).thenReturn(payment(PaymentStatus.AUTHORIZED));

        consumer.consume(event(BookingEventType.CANCELLED));

        verify(paymentFacade).cancel(eq(1L), any(ActionContext.class));
        verify(paymentFacade, never()).refund(any(), any(), any());
    }

    @Test
    void cancelledBookingWithTerminalPaymentIsANoOp() {
        when(paymentService.getByBookingId(42L)).thenReturn(payment(PaymentStatus.REFUNDED));

        consumer.consume(event(BookingEventType.CANCELLED));

        verifyNoInteractions(paymentFacade);
    }

    @Test
    void cancelledBookingWithoutAnyPaymentIsANoOp() {
        when(paymentService.getByBookingId(42L)).thenThrow(PaymentNotFoundException.byBooking(42L));

        consumer.consume(event(BookingEventType.CANCELLED));

        verifyNoInteractions(paymentFacade);
    }
}
