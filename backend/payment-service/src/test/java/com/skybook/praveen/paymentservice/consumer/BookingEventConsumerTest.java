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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                status, PaymentMethod.CARD, null, null, null, null, "owner@test.com", List.of(), List.of(), 0L, now, now);
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

    // ---------------------------------------------------------------
    // PARTIALLY_CANCELLED - the actual money for partial cancellations
    // ---------------------------------------------------------------

    private BookingEvent partialEvent(Integer tierPercent, String breakdown) {
        return BookingEvent.builder()
                .type(BookingEventType.PARTIALLY_CANCELLED)
                .bookingId(42L)
                .bookingReference("SBTEST")
                .refundTierPercent(tierPercent)
                .refundBreakdown(breakdown)
                .build();
    }

    @Test
    void partialCancellationRefundsExactlyTheCancelledLinesAtTheTier() {
        when(paymentService.getByBookingId(42L)).thenReturn(payment(PaymentStatus.CAPTURED));

        consumer.consume(partialEvent(50, "SAVER:80.00;FLEXI:120.00"));

        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(paymentFacade).refund(eq(1L), captor.capture(), any(ActionContext.class));
        RefundRequest request = captor.getValue();
        assertThat(request.refundPercent()).isEqualTo(50);
        assertThat(request.fareLines()).hasSize(2);
        assertThat(request.fareLines().get(0).fareType()).isEqualTo("SAVER");
        assertThat(request.fareLines().get(0).amount()).isEqualByComparingTo("80.00");
        assertThat(request.reason()).contains("SBTEST");
    }

    @Test
    void theSamePartialCancellationDeliveredTwiceRefundsONCE() {
        // THE live defect this increment exists to kill (IDEMPOTENCY §2.5):
        // after the first partial refund the payment sits in exactly the
        // PARTIALLY_REFUNDED state the status guard accepts, so a Kafka
        // redelivery sailed through and created a SECOND refund and a second
        // gateway call. The cause key + V3 unique index now refuse the second
        // insert; the consumer must treat that refusal as "already done" -
        // swallowing it, not rethrowing into the retry loop that caused the
        // redelivery in the first place.
        BookingEvent event = BookingEvent.builder()
                .type(BookingEventType.PARTIALLY_CANCELLED)
                .bookingId(42L)
                .bookingReference("SBTEST")
                .refundTierPercent(100)
                .refundBreakdown("FLEXI:120.00")
                .cancelledBookingPassengerIds(java.util.List.of(14L, 12L))
                .build();
        when(paymentService.getByBookingId(42L))
                .thenReturn(payment(PaymentStatus.CAPTURED))
                .thenReturn(payment(PaymentStatus.PARTIALLY_REFUNDED));
        // First delivery refunds; the redelivery's insert dies on the unique
        // index, surfaced as DataIntegrityViolationException.
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        when(paymentFacade.refund(eq(1L), captor.capture(), any(ActionContext.class)))
                .thenReturn(null)
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_refunds_payment_source"));

        consumer.consume(event);
        assertThatCode(() -> consumer.consume(event)).doesNotThrowAnyException();

        verify(paymentFacade, times(2)).refund(eq(1L), any(RefundRequest.class), any(ActionContext.class));
        // Deterministic cause, ids SORTED - the redelivery names the same one.
        assertThat(captor.getAllValues())
                .extracting(RefundRequest::sourceReference)
                .containsExactly("partial:42:12,14", "partial:42:12,14");
    }

    @Test
    void partialCancellationInTheZeroWindowKeepsTheMoney() {
        when(paymentService.getByBookingId(42L)).thenReturn(payment(PaymentStatus.CAPTURED));

        consumer.consume(partialEvent(0, "FLEXI:120.00"));

        verifyNoInteractions(paymentFacade);
    }

    @Test
    void partialCancellationWithoutCapturedMoneyIsANoOp() {
        when(paymentService.getByBookingId(42L)).thenReturn(payment(PaymentStatus.PENDING));

        consumer.consume(partialEvent(100, "FLEXI:120.00"));

        verifyNoInteractions(paymentFacade);
    }

    @Test
    void partialCancellationWithoutABreakdownRefusesToRefundBlind() {
        when(paymentService.getByBookingId(42L)).thenReturn(payment(PaymentStatus.CAPTURED));

        consumer.consume(partialEvent(100, null));

        verifyNoInteractions(paymentFacade);
    }
}
