package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * booking-service confirms bookings off PAYMENT_SUCCEEDED, so this event
 * moves real money and real state. The money fields stay BigDecimal end to
 * end, and the branch-specific fields (refund amounts, failure reason,
 * invoice number) stay null on the branches they do not belong to.
 */
class PaymentEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(WRITE_DATES_AS_TIMESTAMPS);

    private static final LocalDateTime OCCURRED = LocalDateTime.of(2026, 7, 4, 2, 26);

    @Test
    @DisplayName("the builder carries every field through to the getters")
    void builderCarriesEveryField() {
        PaymentEvent event = PaymentEvent.builder()
                .type(PaymentEventType.REFUND_COMPLETED)
                .paymentReference("PAY-2026-K7M4Z9")
                .bookingId(77L)
                .bookingReference("SB1234")
                .amount(new BigDecimal("240.00"))
                .currency("USD")
                .refundedAmount(new BigDecimal("120.00"))
                .cancellationFee(new BigDecimal("120.00"))
                .failureReason(null)
                .invoiceNumber("INV-2026-0007")
                .occurredAt(OCCURRED)
                .build();

        assertThat(event.getType()).isEqualTo(PaymentEventType.REFUND_COMPLETED);
        assertThat(event.getPaymentReference()).isEqualTo("PAY-2026-K7M4Z9");
        assertThat(event.getBookingId()).isEqualTo(77L);
        assertThat(event.getBookingReference()).isEqualTo("SB1234");
        assertThat(event.getAmount()).isEqualByComparingTo("240.00");
        assertThat(event.getCurrency()).isEqualTo("USD");
        assertThat(event.getRefundedAmount()).isEqualByComparingTo("120.00");
        assertThat(event.getCancellationFee()).isEqualByComparingTo("120.00");
        assertThat(event.getFailureReason()).isNull();
        assertThat(event.getInvoiceNumber()).isEqualTo("INV-2026-0007");
        assertThat(event.getOccurredAt()).isEqualTo(OCCURRED);
    }

    @Test
    @DisplayName("the all-args constructor matches the declared field order")
    void allArgsConstructorMatchesFieldOrder() {
        PaymentEvent event = new PaymentEvent(
                PaymentEventType.PAYMENT_SUCCEEDED, "PAY-2026-K7M4Z9", 77L, "SB1234",
                new BigDecimal("240.00"), "USD", null, null, null, "INV-2026-0007", OCCURRED);

        assertThat(event.getType()).isEqualTo(PaymentEventType.PAYMENT_SUCCEEDED);
        assertThat(event.getPaymentReference()).isEqualTo("PAY-2026-K7M4Z9");
        assertThat(event.getBookingId()).isEqualTo(77L);
        assertThat(event.getBookingReference()).isEqualTo("SB1234");
        assertThat(event.getAmount()).isEqualByComparingTo("240.00");
        assertThat(event.getInvoiceNumber()).isEqualTo("INV-2026-0007");
        assertThat(event.getOccurredAt()).isEqualTo(OCCURRED);
    }

    @Test
    @DisplayName("the no-args constructor leaves everything null for the deserializer")
    void noArgsConstructorLeavesEverythingNull() {
        PaymentEvent event = new PaymentEvent();

        assertThat(event.getType()).isNull();
        assertThat(event.getAmount()).isNull();
        assertThat(event.getRefundedAmount()).isNull();
        assertThat(event.getCancellationFee()).isNull();
        assertThat(event.getFailureReason()).isNull();
        assertThat(event.getInvoiceNumber()).isNull();
        assertThat(event.getOccurredAt()).isNull();
    }

    @Test
    @DisplayName("setters rewrite each field")
    void settersRewriteFields() {
        PaymentEvent event = new PaymentEvent();
        event.setType(PaymentEventType.PAYMENT_FAILED);
        event.setFailureReason("Card declined");
        event.setAmount(new BigDecimal("10.00"));

        assertThat(event.getType()).isEqualTo(PaymentEventType.PAYMENT_FAILED);
        assertThat(event.getFailureReason()).isEqualTo("Card declined");
        assertThat(event.getAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("a success event carries an invoice and no failure reason")
    void successCarriesAnInvoiceAndNoFailureReason() {
        PaymentEvent event = PaymentEvent.builder()
                .type(PaymentEventType.PAYMENT_SUCCEEDED)
                .invoiceNumber("INV-2026-0007")
                .amount(new BigDecimal("240.00"))
                .build();

        assertThat(event.getInvoiceNumber()).isNotNull();
        assertThat(event.getFailureReason()).isNull();
        assertThat(event.getRefundedAmount()).isNull();
        assertThat(event.getCancellationFee()).isNull();
    }

    @Test
    @DisplayName("a failure event carries the gateway message and no invoice")
    void failureCarriesTheGatewayMessage() {
        PaymentEvent event = PaymentEvent.builder()
                .type(PaymentEventType.PAYMENT_FAILED)
                .failureReason("Insufficient funds")
                .build();

        assertThat(event.getFailureReason()).isEqualTo("Insufficient funds");
        assertThat(event.getInvoiceNumber()).isNull();
    }

    @Test
    @DisplayName("a refund splits the captured amount into refunded plus withheld fee")
    void refundSplitsTheCapturedAmount() {
        PaymentEvent event = PaymentEvent.builder()
                .type(PaymentEventType.REFUND_COMPLETED)
                .amount(new BigDecimal("240.00"))
                .refundedAmount(new BigDecimal("168.00"))
                .cancellationFee(new BigDecimal("72.00"))
                .build();

        assertThat(event.getRefundedAmount().add(event.getCancellationFee()))
                .isEqualByComparingTo(event.getAmount());
    }

    @Test
    @DisplayName("money keeps its scale through a JSON round trip - no double rounding")
    void moneySurvivesAJsonRoundTrip() throws Exception {
        PaymentEvent original = PaymentEvent.builder()
                .type(PaymentEventType.REFUND_COMPLETED)
                .paymentReference("PAY-2026-K7M4Z9")
                .amount(new BigDecimal("240.10"))
                .refundedAmount(new BigDecimal("120.05"))
                .cancellationFee(new BigDecimal("120.05"))
                .occurredAt(OCCURRED)
                .build();

        PaymentEvent parsed = MAPPER.readValue(MAPPER.writeValueAsString(original), PaymentEvent.class);

        assertThat(parsed.getAmount()).isEqualByComparingTo("240.10");
        assertThat(parsed.getRefundedAmount()).isEqualByComparingTo("120.05");
        assertThat(parsed.getCancellationFee()).isEqualByComparingTo("120.05");
        assertThat(parsed.getOccurredAt()).isEqualTo(OCCURRED);
        assertThat(parsed.getAmount()).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("every payment event type can be built without any other field set")
    void everyTypeCanStandAlone() {
        for (PaymentEventType type : PaymentEventType.values()) {
            assertThat(PaymentEvent.builder().type(type).build().getType()).isEqualTo(type);
        }
    }
}
