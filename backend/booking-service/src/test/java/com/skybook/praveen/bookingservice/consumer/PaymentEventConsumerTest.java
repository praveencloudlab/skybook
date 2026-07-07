package com.skybook.praveen.bookingservice.consumer;

import com.skybook.praveen.common.event.PaymentEvent;
import com.skybook.praveen.common.event.PaymentEventType;
import com.skybook.praveen.bookingservice.exception.BookingNotFoundException;
import com.skybook.praveen.bookingservice.facade.BookingFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private BookingFacade bookingFacade;

    @InjectMocks
    private PaymentEventConsumer consumer;

    private PaymentEvent event(PaymentEventType type, Long bookingId) {
        return PaymentEvent.builder()
                .type(type)
                .paymentReference("PAY-2026-K7M4Z9")
                .bookingId(bookingId)
                .bookingReference("SBTEST")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .occurredAt(LocalDateTime.now())
                .build();
    }

    @Test
    void paymentSucceededConfirmsTheBooking() {
        consumer.consume(event(PaymentEventType.PAYMENT_SUCCEEDED, 42L));

        verify(bookingFacade).confirmBookingFromPayment(42L, "PAY-2026-K7M4Z9");
    }

    @Test
    void eventsWithoutABookingIdAreSkipped() {
        consumer.consume(event(PaymentEventType.PAYMENT_SUCCEEDED, null));

        verify(bookingFacade, never()).confirmBookingFromPayment(anyLong(), any());
    }

    @Test
    void paymentFailedLeavesTheBookingAlone() {
        consumer.consume(event(PaymentEventType.PAYMENT_FAILED, 42L));

        verify(bookingFacade, never()).confirmBookingFromPayment(anyLong(), any());
    }

    @Test
    void refundAndCancellationEventsAreInformational() {
        consumer.consume(event(PaymentEventType.REFUND_COMPLETED, 42L));
        consumer.consume(event(PaymentEventType.PAYMENT_CANCELLED, 42L));

        verify(bookingFacade, never()).confirmBookingFromPayment(anyLong(), any());
    }

    @Test
    void processingFailuresAreSwallowedNotRethrown() {
        // A booking-side bug must not poison the payment topic with redeliveries.
        when(bookingFacade.confirmBookingFromPayment(42L, "PAY-2026-K7M4Z9"))
                .thenThrow(new BookingNotFoundException(42L));

        assertThatCode(() -> consumer.consume(event(PaymentEventType.PAYMENT_SUCCEEDED, 42L)))
                .doesNotThrowAnyException();
    }
}
