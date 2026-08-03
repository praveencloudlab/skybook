package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One leg of a journey (ROUND_TRIP_MODULE.md section 6): flight context stated
 * once per leg with the passengers nested underneath, instead of repeated on
 * every passenger row.
 */
class BookingEventSegmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the builder carries every field through to the getters")
    void builderCarriesEveryField() {
        BookingEventSegment segment = BookingEventSegment.builder()
                .segmentIndex(0)
                .flightId(9L)
                .flightNumber("SB101")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime("2026-07-08 21:25")
                .arrivalTime("2026-07-09 00:40")
                .departureTerminal("5")
                .arrivalTerminal("7")
                .passengers(List.of(BookingEventPassenger.builder().name("Ann").build()))
                .build();

        assertThat(segment.getSegmentIndex()).isZero();
        assertThat(segment.getFlightId()).isEqualTo(9L);
        assertThat(segment.getFlightNumber()).isEqualTo("SB101");
        assertThat(segment.getOriginAirportCode()).isEqualTo("LHR");
        assertThat(segment.getDestinationAirportCode()).isEqualTo("JFK");
        assertThat(segment.getDepartureTime()).isEqualTo("2026-07-08 21:25");
        assertThat(segment.getArrivalTime()).isEqualTo("2026-07-09 00:40");
        assertThat(segment.getDepartureTerminal()).isEqualTo("5");
        assertThat(segment.getArrivalTerminal()).isEqualTo("7");
        assertThat(segment.getPassengers()).singleElement()
                .extracting(BookingEventPassenger::getName).isEqualTo("Ann");
    }

    @Test
    @DisplayName("the all-args constructor matches the declared field order")
    void allArgsConstructorMatchesFieldOrder() {
        BookingEventSegment segment = new BookingEventSegment(
                1, 10L, "SB102", "JFK", "LHR",
                "2026-07-15 08:00", "2026-07-15 20:10", "7", "5", List.of());

        assertThat(segment.getSegmentIndex()).isEqualTo(1);
        assertThat(segment.getFlightId()).isEqualTo(10L);
        assertThat(segment.getFlightNumber()).isEqualTo("SB102");
        assertThat(segment.getOriginAirportCode()).isEqualTo("JFK");
        assertThat(segment.getDestinationAirportCode()).isEqualTo("LHR");
        assertThat(segment.getDepartureTerminal()).isEqualTo("7");
        assertThat(segment.getArrivalTerminal()).isEqualTo("5");
        assertThat(segment.getPassengers()).isEmpty();
    }

    @Test
    @DisplayName("the no-args constructor leaves everything null")
    void noArgsConstructorLeavesEverythingNull() {
        BookingEventSegment segment = new BookingEventSegment();

        assertThat(segment.getSegmentIndex()).isNull();
        assertThat(segment.getFlightId()).isNull();
        assertThat(segment.getFlightNumber()).isNull();
        assertThat(segment.getPassengers()).isNull();
        assertThat(segment.getDepartureTerminal()).isNull();
    }

    @Test
    @DisplayName("terminals are optional - a pre-terminals event leaves them null")
    void terminalsAreOptional() throws Exception {
        BookingEventSegment segment = MAPPER.readValue(
                "{\"segmentIndex\":0,\"flightNumber\":\"SB101\"}", BookingEventSegment.class);

        assertThat(segment.getFlightNumber()).isEqualTo("SB101");
        assertThat(segment.getDepartureTerminal()).isNull();
        assertThat(segment.getArrivalTerminal()).isNull();
    }

    @Test
    @DisplayName("setters rewrite each field")
    void settersRewriteFields() {
        BookingEventSegment segment = new BookingEventSegment();
        segment.setSegmentIndex(1);
        segment.setFlightNumber("SB102");
        segment.setArrivalTerminal("2");
        segment.setPassengers(List.of(BookingEventPassenger.builder().name("Bob").build()));

        assertThat(segment.getSegmentIndex()).isEqualTo(1);
        assertThat(segment.getFlightNumber()).isEqualTo("SB102");
        assertThat(segment.getArrivalTerminal()).isEqualTo("2");
        assertThat(segment.getPassengers()).hasSize(1);
    }

    @Test
    @DisplayName("passengers nest under their own leg, so the same traveller can hold two seats")
    void passengersNestUnderTheirOwnLeg() {
        BookingEventPassenger outbound = BookingEventPassenger.builder()
                .name("Ann").segmentIndex(0).seatNumber("12A").build();
        BookingEventPassenger inbound = BookingEventPassenger.builder()
                .name("Ann").segmentIndex(1).seatNumber("14C").build();

        List<BookingEventSegment> legs = List.of(
                BookingEventSegment.builder().segmentIndex(0).passengers(List.of(outbound)).build(),
                BookingEventSegment.builder().segmentIndex(1).passengers(List.of(inbound)).build());

        assertThat(legs.get(0).getPassengers().get(0).getSeatNumber()).isEqualTo("12A");
        assertThat(legs.get(1).getPassengers().get(0).getSeatNumber()).isEqualTo("14C");
    }

    @Test
    @DisplayName("a leg survives a JSON round trip with its nested passengers intact")
    void jsonRoundTripPreservesNestedPassengers() throws Exception {
        BookingEventSegment original = BookingEventSegment.builder()
                .segmentIndex(0)
                .flightId(9L)
                .flightNumber("SB101")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTerminal("5")
                .passengers(List.of(BookingEventPassenger.builder()
                        .name("Ann").seatNumber("12A").bookingPassengerId(11L).build()))
                .build();

        BookingEventSegment parsed =
                MAPPER.readValue(MAPPER.writeValueAsString(original), BookingEventSegment.class);

        assertThat(parsed.getFlightNumber()).isEqualTo("SB101");
        assertThat(parsed.getDepartureTerminal()).isEqualTo("5");
        assertThat(parsed.getPassengers()).singleElement()
                .satisfies(p -> {
                    assertThat(p.getName()).isEqualTo("Ann");
                    assertThat(p.getBookingPassengerId()).isEqualTo(11L);
                });
    }
}
