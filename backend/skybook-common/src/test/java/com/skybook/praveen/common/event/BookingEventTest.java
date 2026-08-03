package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BookingEvent is the widest contract on the bus - notification, payment and
 * check-in services all read it, and each of them was allowed to add fields
 * without a lock-step deploy. The tests below pin the two rules that make
 * that safe: an event without {@code segments} must still be fully readable
 * through the deprecated top-level mirror, and an unknown field must not stop
 * an older consumer from deserializing.
 */
class BookingEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectMapper lenientMapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static BookingEventPassenger passenger(String name, String seat) {
        return BookingEventPassenger.builder()
                .bookingPassengerId(11L)
                .name(name)
                .seatNumber(seat)
                .travelClass("ECONOMY")
                .fareType("SAVER")
                .fare(new BigDecimal("120.00"))
                .currency("USD")
                .build();
    }

    @Nested
    @DisplayName("builder and constructor round trips")
    class RoundTrips {

        @Test
        @DisplayName("the builder carries every field through to the getters")
        void builderCarriesEveryField() {
            BookingEvent event = BookingEvent.builder()
                    .type(BookingEventType.CONFIRMED)
                    .bookingReference("SB1234")
                    .contactEmail("praveen@example.com")
                    .contactName("Praveen S")
                    .subject("Your booking is confirmed")
                    .message("plain-text fallback")
                    .bookingId(77L)
                    .bookingStatus("CONFIRMED")
                    .flightId(9L)
                    .bookingDate("2026-07-04 02:26")
                    .flightNumber("SB101")
                    .originAirportCode("LHR")
                    .destinationAirportCode("JFK")
                    .departureTime("2026-07-08 21:25")
                    .arrivalTime("2026-07-09 00:40")
                    .passengers(List.of(passenger("Ann", "12A")))
                    .totalFare(new BigDecimal("240.00"))
                    .currency("USD")
                    .paymentStatus("PAID")
                    .ownerSubject("auth|abc-123")
                    .build();

            assertThat(event.getType()).isEqualTo(BookingEventType.CONFIRMED);
            assertThat(event.getBookingReference()).isEqualTo("SB1234");
            assertThat(event.getContactEmail()).isEqualTo("praveen@example.com");
            assertThat(event.getContactName()).isEqualTo("Praveen S");
            assertThat(event.getSubject()).isEqualTo("Your booking is confirmed");
            assertThat(event.getMessage()).isEqualTo("plain-text fallback");
            assertThat(event.getBookingId()).isEqualTo(77L);
            assertThat(event.getBookingStatus()).isEqualTo("CONFIRMED");
            assertThat(event.getFlightId()).isEqualTo(9L);
            assertThat(event.getBookingDate()).isEqualTo("2026-07-04 02:26");
            assertThat(event.getFlightNumber()).isEqualTo("SB101");
            assertThat(event.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(event.getDestinationAirportCode()).isEqualTo("JFK");
            assertThat(event.getDepartureTime()).isEqualTo("2026-07-08 21:25");
            assertThat(event.getArrivalTime()).isEqualTo("2026-07-09 00:40");
            assertThat(event.getPassengers()).singleElement()
                    .extracting(BookingEventPassenger::getName).isEqualTo("Ann");
            assertThat(event.getTotalFare()).isEqualByComparingTo("240.00");
            assertThat(event.getCurrency()).isEqualTo("USD");
            assertThat(event.getPaymentStatus()).isEqualTo("PAID");
            assertThat(event.getOwnerSubject()).isEqualTo("auth|abc-123");
        }

        @Test
        @DisplayName("the no-args constructor leaves every field null - Kafka deserializes into it")
        void noArgsConstructorLeavesEverythingNull() {
            BookingEvent event = new BookingEvent();

            assertThat(event.getType()).isNull();
            assertThat(event.getBookingReference()).isNull();
            assertThat(event.getBookingId()).isNull();
            assertThat(event.getSegments()).isNull();
            assertThat(event.getPassengers()).isNull();
            assertThat(event.getTotalFare()).isNull();
            assertThat(event.getRefundTierPercent()).isNull();
            assertThat(event.getRefundBreakdown()).isNull();
            assertThat(event.getCancelledBookingPassengerIds()).isNull();
            assertThat(event.getOwnerSubject()).isNull();
        }

        @Test
        @DisplayName("setters rewrite every field, so a consumer can enrich an event in place")
        void settersRewriteFields() {
            BookingEvent event = new BookingEvent();
            event.setType(BookingEventType.CANCELLED);
            event.setBookingReference("SB9999");
            event.setRefundTierPercent(50);
            event.setSegments(List.of(BookingEventSegment.builder().segmentIndex(0).build()));

            assertThat(event.getType()).isEqualTo(BookingEventType.CANCELLED);
            assertThat(event.getBookingReference()).isEqualTo("SB9999");
            assertThat(event.getRefundTierPercent()).isEqualTo(50);
            assertThat(event.getSegments()).hasSize(1);
        }

        @Test
        @DisplayName("an empty builder produces an all-null event without throwing")
        void emptyBuilderIsTolerated() {
            BookingEvent event = BookingEvent.builder().build();

            assertThat(event).isNotNull();
            assertThat(event.getType()).isNull();
            assertThat(event.getBookingReference()).isNull();
        }

        @Test
        @DisplayName("nulls are accepted everywhere - legacy producers simply omit fields")
        void nullsAreTolerated() {
            BookingEvent event = BookingEvent.builder()
                    .type(null)
                    .bookingReference(null)
                    .passengers(null)
                    .segments(null)
                    .totalFare(null)
                    .refundTierPercent(null)
                    .cancelledBookingPassengerIds(null)
                    .build();

            assertThat(event.getType()).isNull();
            assertThat(event.getPassengers()).isNull();
            assertThat(event.getSegments()).isNull();
        }
    }

    @Nested
    @DisplayName("the deprecated segment-0 mirror (ROUND_TRIP_MODULE.md section 6)")
    class SegmentFallbackCompatibility {

        @Test
        @DisplayName("segments == null still exposes the top-level flight fields and flat passenger list")
        void nullSegmentsStillExposesTheTopLevelMirror() {
            BookingEvent oldStyle = BookingEvent.builder()
                    .type(BookingEventType.CONFIRMED)
                    .bookingReference("SB1234")
                    .flightId(9L)
                    .flightNumber("SB101")
                    .originAirportCode("LHR")
                    .destinationAirportCode("JFK")
                    .departureTime("2026-07-08 21:25")
                    .arrivalTime("2026-07-09 00:40")
                    .passengers(List.of(passenger("Ann", "12A"), passenger("Bob", "12B")))
                    .build();

            // Null segments is the marker for "old event" - the fallback must be complete.
            assertThat(oldStyle.getSegments()).isNull();
            assertThat(oldStyle.getFlightId()).isEqualTo(9L);
            assertThat(oldStyle.getFlightNumber()).isEqualTo("SB101");
            assertThat(oldStyle.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(oldStyle.getDestinationAirportCode()).isEqualTo("JFK");
            assertThat(oldStyle.getDepartureTime()).isEqualTo("2026-07-08 21:25");
            assertThat(oldStyle.getArrivalTime()).isEqualTo("2026-07-09 00:40");
            assertThat(oldStyle.getPassengers()).hasSize(2)
                    .extracting(BookingEventPassenger::getName)
                    .containsExactly("Ann", "Bob");
        }

        @Test
        @DisplayName("a segmented event still mirrors segment 0 onto the top-level fields")
        void segmentedEventKeepsTheSegmentZeroMirror() {
            BookingEventSegment outbound = BookingEventSegment.builder()
                    .segmentIndex(0)
                    .flightId(9L)
                    .flightNumber("SB101")
                    .originAirportCode("LHR")
                    .destinationAirportCode("JFK")
                    .departureTime("2026-07-08 21:25")
                    .arrivalTime("2026-07-09 00:40")
                    .passengers(List.of(passenger("Ann", "12A")))
                    .build();
            BookingEventSegment inbound = BookingEventSegment.builder()
                    .segmentIndex(1)
                    .flightId(10L)
                    .flightNumber("SB102")
                    .originAirportCode("JFK")
                    .destinationAirportCode("LHR")
                    .departureTime("2026-07-15 08:00")
                    .arrivalTime("2026-07-15 20:10")
                    .passengers(List.of(passenger("Ann", "14C")))
                    .build();

            BookingEvent roundTrip = BookingEvent.builder()
                    .type(BookingEventType.CONFIRMED)
                    .bookingReference("SB1234")
                    .segments(List.of(outbound, inbound))
                    // the deprecated mirror: segment 0 restated at the top level
                    .flightId(outbound.getFlightId())
                    .flightNumber(outbound.getFlightNumber())
                    .originAirportCode(outbound.getOriginAirportCode())
                    .destinationAirportCode(outbound.getDestinationAirportCode())
                    .departureTime(outbound.getDepartureTime())
                    .arrivalTime(outbound.getArrivalTime())
                    .passengers(outbound.getPassengers())
                    .build();

            // New consumers prefer segments...
            assertThat(roundTrip.getSegments()).hasSize(2)
                    .extracting(BookingEventSegment::getSegmentIndex)
                    .containsExactly(0, 1);
            // ...old consumers read the mirror and see the outbound leg.
            assertThat(roundTrip.getFlightNumber()).isEqualTo("SB101");
            assertThat(roundTrip.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(roundTrip.getPassengers()).isEqualTo(outbound.getPassengers());
        }

        @Test
        @DisplayName("an old producer's JSON with no segments key deserializes with segments null")
        void jsonWithoutSegmentsDeserializesToNullSegments() throws Exception {
            String legacyJson = """
                    {
                      "type": "CONFIRMED",
                      "bookingReference": "SB1234",
                      "contactEmail": "praveen@example.com",
                      "bookingId": 77,
                      "flightId": 9,
                      "flightNumber": "SB101",
                      "originAirportCode": "LHR",
                      "destinationAirportCode": "JFK",
                      "departureTime": "2026-07-08 21:25",
                      "arrivalTime": "2026-07-09 00:40",
                      "totalFare": 240.00,
                      "currency": "USD",
                      "passengers": [
                        {"name": "Ann", "seatNumber": "12A", "travelClass": "ECONOMY"}
                      ]
                    }""";

            BookingEvent event = MAPPER.readValue(legacyJson, BookingEvent.class);

            assertThat(event.getSegments()).isNull();
            assertThat(event.getType()).isEqualTo(BookingEventType.CONFIRMED);
            assertThat(event.getFlightNumber()).isEqualTo("SB101");
            assertThat(event.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(event.getDestinationAirportCode()).isEqualTo("JFK");
            assertThat(event.getTotalFare()).isEqualByComparingTo("240.00");
            assertThat(event.getPassengers()).singleElement()
                    .satisfies(p -> {
                        assertThat(p.getName()).isEqualTo("Ann");
                        assertThat(p.getSeatNumber()).isEqualTo("12A");
                        // fields added later are simply absent, never defaulted
                        assertThat(p.getBookingPassengerId()).isNull();
                        assertThat(p.getSegmentIndex()).isNull();
                        assertThat(p.getSeatSurcharge()).isNull();
                    });
        }

        @Test
        @DisplayName("a legacy CANCELLED event has a null refundTierPercent - consumers read that as 100")
        void legacyCancelledEventHasNoRefundTier() throws Exception {
            BookingEvent event = MAPPER.readValue(
                    "{\"type\":\"CANCELLED\",\"bookingReference\":\"SB1234\",\"bookingId\":77}",
                    BookingEvent.class);

            assertThat(event.getType()).isEqualTo(BookingEventType.CANCELLED);
            assertThat(event.getRefundTierPercent()).isNull();
            assertThat(event.getRefundBreakdown()).isNull();
            assertThat(event.getCancelledBookingPassengerIds()).isNull();
        }

        @Test
        @DisplayName("a PARTIALLY_CANCELLED event carries the breakdown, tier and cancelled row ids")
        void partiallyCancelledCarriesTheRefundInstructions() throws Exception {
            BookingEvent event = BookingEvent.builder()
                    .type(BookingEventType.PARTIALLY_CANCELLED)
                    .bookingReference("SB1234")
                    .bookingStatus("PARTIALLY_CANCELLED")
                    .refundTierPercent(50)
                    .refundBreakdown("FLEXI:100.00;SAVER:80.00")
                    .cancelledBookingPassengerIds(List.of(11L, 12L))
                    .build();

            String json = MAPPER.writeValueAsString(event);
            BookingEvent parsed = MAPPER.readValue(json, BookingEvent.class);

            assertThat(parsed.getType()).isEqualTo(BookingEventType.PARTIALLY_CANCELLED);
            assertThat(parsed.getRefundTierPercent()).isEqualTo(50);
            assertThat(parsed.getRefundBreakdown()).isEqualTo("FLEXI:100.00;SAVER:80.00");
            assertThat(parsed.getCancelledBookingPassengerIds()).containsExactly(11L, 12L);
        }

        @Test
        @DisplayName("a nested-segment event survives a JSON round trip with its passengers under the legs")
        void segmentedEventSurvivesAJsonRoundTrip() throws Exception {
            BookingEvent event = BookingEvent.builder()
                    .type(BookingEventType.CONFIRMED)
                    .bookingReference("SB1234")
                    .segments(List.of(
                            BookingEventSegment.builder()
                                    .segmentIndex(0).flightNumber("SB101")
                                    .passengers(List.of(passenger("Ann", "12A"))).build(),
                            BookingEventSegment.builder()
                                    .segmentIndex(1).flightNumber("SB102")
                                    .passengers(List.of(passenger("Ann", "14C"))).build()))
                    .build();

            BookingEvent parsed = MAPPER.readValue(MAPPER.writeValueAsString(event), BookingEvent.class);

            assertThat(parsed.getSegments()).hasSize(2);
            assertThat(parsed.getSegments().get(1).getFlightNumber()).isEqualTo("SB102");
            assertThat(parsed.getSegments().get(1).getPassengers())
                    .singleElement()
                    .extracting(BookingEventPassenger::getSeatNumber).isEqualTo("14C");
        }

        @Test
        @DisplayName("an unknown future field does not break a lenient (older) consumer")
        void unknownFieldDoesNotBreakAnOlderConsumer() throws Exception {
            String futureJson = """
                    {"type":"CONFIRMED","bookingReference":"SB1234","loyaltyTierAddedLater":"GOLD"}""";

            BookingEvent event = lenientMapper().readValue(futureJson, BookingEvent.class);

            assertThat(event.getType()).isEqualTo(BookingEventType.CONFIRMED);
            assertThat(event.getBookingReference()).isEqualTo("SB1234");
        }
    }

    @Nested
    @DisplayName("ownership and money fields")
    class OwnershipAndMoney {

        @Test
        @DisplayName("ownerSubject rides every event type so consumers can snapshot it")
        void ownerSubjectRidesEveryType() {
            for (BookingEventType type : BookingEventType.values()) {
                BookingEvent event = BookingEvent.builder()
                        .type(type)
                        .ownerSubject("auth|owner-1")
                        .build();
                assertThat(event.getOwnerSubject())
                        .as("ownerSubject on %s", type)
                        .isEqualTo("auth|owner-1");
            }
        }

        @Test
        @DisplayName("totalFare keeps its scale - money is BigDecimal, never a double")
        void totalFareKeepsItsScale() throws Exception {
            BookingEvent event = BookingEvent.builder().totalFare(new BigDecimal("240.50")).build();

            assertThat(event.getTotalFare()).isInstanceOf(BigDecimal.class);
            assertThat(event.getTotalFare().scale()).isEqualTo(2);

            BookingEvent parsed = MAPPER.readValue(MAPPER.writeValueAsString(event), BookingEvent.class);
            assertThat(parsed.getTotalFare()).isEqualByComparingTo("240.50");
        }
    }
}
