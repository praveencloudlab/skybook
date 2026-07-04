package com.skybook.praveen.paymentservice.integration;

import com.skybook.praveen.paymentservice.dto.response.InvoiceResponse;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full journey through real HTTP, PostgreSQL and Kafka - including the
 * BookingEvent consumer as the entry point, exactly as production will work:
 * booking CREATED event -> auto payment -> authorize -> capture (invoice) ->
 * refund -> payment events back out on the topic.
 */
class PaymentApiKafkaIntegrationTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void bookingEventDrivesTheFullPaymentLifecycle() {

        long bookingId = 777_001;

        // 1. booking-service publishes CREATED (simulated with a raw producer).
        publishBookingCreated(bookingId, "SBKAFA", "260.00",
                "{\"name\":\"A\",\"fareType\":\"FLEXI\",\"fare\":100.00}," +
                "{\"name\":\"B\",\"fareType\":\"SAVER\",\"fare\":160.00}");

        // 2. The consumer auto-creates a PENDING payment.
        PaymentResponse payment = awaitPaymentForBooking(bookingId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.amount()).isEqualByComparingTo("260.00");

        // 3. Authorize (simulated gateway).
        PaymentResponse authorized = patch("/api/payments/" + payment.id() + "/authorize", PaymentResponse.class);
        assertThat(authorized.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(authorized.gatewayReference()).startsWith("SIM-");

        // 4. Capture - money moves, invoice exists from this moment.
        PaymentResponse captured = patch("/api/payments/" + payment.id() + "/capture", PaymentResponse.class);
        assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(captured.capturedAmount()).isEqualByComparingTo("260.00");

        InvoiceResponse invoice = rest.getForObject("/api/invoices/" + payment.id(), InvoiceResponse.class);
        assertThat(invoice.invoiceNumber()).matches("INV-\\d{4}-\\d{6}");
        assertThat(invoice.grandTotal()).isEqualByComparingTo("260.00");

        // 5. Refund - fare-type rules: FLEXI 100 back, SAVER 160 -> 112 back + 48 fee.
        RefundResponse refund = patch("/api/payments/" + payment.id() + "/refund", RefundResponse.class);
        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.amount()).isEqualByComparingTo("212.00");
        assertThat(refund.cancellationFee()).isEqualByComparingTo("48.00");

        PaymentResponse afterRefund = rest.getForObject("/api/payments/" + payment.id(), PaymentResponse.class);
        assertThat(afterRefund.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(afterRefund.refundedAmount()).isEqualByComparingTo("212.00");

        // 6. The facade published payment events for every step.
        assertThat(consumePaymentEventTypes(Duration.ofSeconds(15), 2))
                .contains("PAYMENT_SUCCEEDED", "REFUND_COMPLETED");
    }

    @Test
    void gatewayDeclineSurfacesAs422AndPaymentFailedEvent() {

        long bookingId = 777_002;

        // Direct creation with the magic decline amount (.13).
        ResponseEntity<PaymentResponse> created = rest.postForEntity("/api/payments",
                new HttpEntity<>(Map.of(
                        "bookingId", bookingId,
                        "bookingReference", "SBKAFB",
                        "amount", "50.13",
                        "currency", "USD"), jsonHeaders()),
                PaymentResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long paymentId = created.getBody().id();

        // Authorization declined -> 422, state AUTHORIZATION_FAILED, ledger row kept.
        ResponseEntity<String> declined = rest.exchange("/api/payments/" + paymentId + "/authorize",
                HttpMethod.PATCH, HttpEntity.EMPTY, String.class);
        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        PaymentResponse after = rest.getForObject("/api/payments/" + paymentId, PaymentResponse.class);
        assertThat(after.status()).isEqualTo(PaymentStatus.AUTHORIZATION_FAILED);
        assertThat(after.failureReason()).contains("declined");
        assertThat(after.transactions()).hasSize(1);

        assertThat(consumePaymentEventTypes(Duration.ofSeconds(15), 1))
                .contains("PAYMENT_FAILED");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void publishBookingCreated(long bookingId, String pnr, String totalFare, String passengersJson) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        String json = """
                {"type":"CREATED","bookingId":%d,"bookingReference":"%s","contactEmail":"t@t.com",
                 "contactName":"Test","subject":"s","message":"m","bookingStatus":"CREATED",
                 "totalFare":%s,"currency":"USD","passengers":[%s]}
                """.formatted(bookingId, pnr, totalFare, passengersJson);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("skybook.booking.events", json));
            producer.flush();
        }
    }

    private PaymentResponse awaitPaymentForBooking(long bookingId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            // Poll untyped: while the consumer hasn't created the payment yet
            // this returns a 404 ErrorResponse whose numeric "status" field
            // cannot deserialize into PaymentResponse's status enum.
            ResponseEntity<String> probe = rest.getForEntity(
                    "/api/payments/booking/" + bookingId, String.class);
            if (probe.getStatusCode().is2xxSuccessful()) {
                return rest.getForObject("/api/payments/booking/" + bookingId, PaymentResponse.class);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Consumer did not create a payment for booking " + bookingId + " within 20s");
    }

    private <T> T patch(String path, Class<T> type) {
        ResponseEntity<T> response = rest.exchange(path, HttpMethod.PATCH, HttpEntity.EMPTY, type);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("PATCH %s -> %s", path, response.getStatusCode())
                .isTrue();
        return response.getBody();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Set<String> consumePaymentEventTypes(Duration timeout, int expectedMinimum) {
        Properties props = new Properties();
        props.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));

        Set<String> types = new HashSet<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("skybook.payment.events"));
            while (System.currentTimeMillis() < deadline && types.size() < expectedMinimum) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    String value = record.value();
                    int i = value.indexOf("\"type\":\"");
                    if (i >= 0) {
                        int start = i + 8;
                        types.add(value.substring(start, value.indexOf('"', start)));
                    }
                }
            }
        }
        return types;
    }
}
