package com.skybook.praveen.checkinservice.integration;

import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
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
 * The consumer's real journey through Kafka + PostgreSQL + real HTTP -
 * scoped to the DB-only paths (AbstractCheckInIntegrationTest's Javadoc
 * explains why checkIn/board/changeSeat, which need flight-service/
 * inventory-service, aren't exercised here): booking CONFIRMED -> one
 * CheckIn per passenger -> booking CANCELLED -> cascade + CheckInEvent out.
 */
class CheckInBookingEventKafkaIntegrationTest extends AbstractCheckInIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void bookingConfirmedEventCreatesOneCheckInPerPassenger() {

        long bookingId = 555_001;
        publishBookingConfirmed(bookingId, "SBKAFA",
                "{\"bookingPassengerId\":9001,\"name\":\"Alice Test\",\"seatNumber\":\"12A\","
                        + "\"travelClass\":\"ECONOMY\",\"fareType\":\"FLEXI\",\"fare\":100.00},"
                        + "{\"bookingPassengerId\":9002,\"name\":\"Bob Test\",\"seatNumber\":\"12B\","
                        + "\"travelClass\":\"ECONOMY\",\"fareType\":\"SAVER\",\"fare\":80.00}");

        List<CheckInResponse> checkIns = awaitCheckInsForBooking(bookingId, 2);

        assertThat(checkIns).extracting(CheckInResponse::passengerName)
                .containsExactlyInAnyOrder("Alice Test", "Bob Test");
        assertThat(checkIns).allMatch(c -> c.status() == CheckInStatus.NOT_OPEN);
        assertThat(checkIns).extracting(CheckInResponse::bookingPassengerId)
                .containsExactlyInAnyOrder(9001L, 9002L);
    }

    @Test
    void duplicateConfirmedEventIsIdempotent() {

        long bookingId = 555_002;
        String passengers = "{\"bookingPassengerId\":9101,\"name\":\"Carol Test\",\"seatNumber\":\"14C\","
                + "\"travelClass\":\"ECONOMY\",\"fareType\":\"FLEXI\",\"fare\":100.00}";

        publishBookingConfirmed(bookingId, "SBKAFB", passengers);
        awaitCheckInsForBooking(bookingId, 1);

        // Redelivery of the same event must not create a second row.
        publishBookingConfirmed(bookingId, "SBKAFB", passengers);
        sleep(2000);

        List<CheckInResponse> checkIns = getCheckInsForBooking(bookingId);
        assertThat(checkIns).hasSize(1);
    }

    @Test
    void bookingCancelledEventCascadesAndPublishesCheckInCancelledEvent() {

        long bookingId = 555_003;
        publishBookingConfirmed(bookingId, "SBKAFC",
                "{\"bookingPassengerId\":9201,\"name\":\"Dave Test\",\"seatNumber\":\"16D\","
                        + "\"travelClass\":\"ECONOMY\",\"fareType\":\"FLEXI\",\"fare\":100.00}");
        awaitCheckInsForBooking(bookingId, 1);

        publishBookingCancelled(bookingId, "SBKAFC");

        List<CheckInResponse> cancelled = awaitCheckInsInStatus(bookingId, CheckInStatus.CANCELLED, 1);
        assertThat(cancelled).hasSize(1);

        assertThat(consumeCheckInEventTypes(Duration.ofSeconds(15), 1))
                .contains("PASSENGER_CHECKIN_CANCELLED");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void publishBookingConfirmed(long bookingId, String pnr, String passengersJson) {
        String json = """
                {"type":"CONFIRMED","bookingId":%d,"bookingReference":"%s","contactEmail":"t@t.com",
                 "contactName":"Test","subject":"s","message":"m","flightId":7,"flightNumber":"BA178",
                 "originAirportCode":"LHR","destinationAirportCode":"JFK","departureTime":"2026-07-08 18:00",
                 "totalFare":180.00,"currency":"USD","passengers":[%s]}
                """.formatted(bookingId, pnr, passengersJson);
        publish(json);
    }

    private void publishBookingCancelled(long bookingId, String pnr) {
        String json = """
                {"type":"CANCELLED","bookingId":%d,"bookingReference":"%s","contactEmail":"t@t.com",
                 "contactName":"Test","subject":"s","message":"m"}
                """.formatted(bookingId, pnr);
        publish(json);
    }

    private void publish(String json) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("skybook.booking.events", json));
            producer.flush();
        }
    }

    private List<CheckInResponse> getCheckInsForBooking(long bookingId) {
        ResponseEntity<List<CheckInResponse>> response = rest.exchange(
                "/api/checkins/booking/" + bookingId, HttpMethod.GET, null,
                new ParameterizedTypeReference<List<CheckInResponse>>() {
                });
        return response.getBody();
    }

    private List<CheckInResponse> awaitCheckInsForBooking(long bookingId, int expectedCount) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<CheckInResponse> checkIns = getCheckInsForBooking(bookingId);
            if (checkIns != null && checkIns.size() >= expectedCount) {
                return checkIns;
            }
            sleep(250);
        }
        throw new AssertionError("Consumer did not create " + expectedCount
                + " check-in(s) for booking " + bookingId + " within 20s");
    }

    private List<CheckInResponse> awaitCheckInsInStatus(long bookingId, CheckInStatus status, int expectedCount) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<CheckInResponse> matching = getCheckInsForBooking(bookingId).stream()
                    .filter(c -> c.status() == status).toList();
            if (matching.size() >= expectedCount) {
                return matching;
            }
            sleep(250);
        }
        throw new AssertionError("Check-in(s) for booking " + bookingId
                + " did not reach " + status + " within 20s");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Set<String> consumeCheckInEventTypes(Duration timeout, int expectedMinimum) {
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
            consumer.subscribe(List.of("skybook.checkin.events"));
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
