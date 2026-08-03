package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boarding-pass event. notification-service is purely event driven, so
 * everything the emailed pass renders - terminals, gate, boarding clock and
 * the signed QR token - has to be snapshotted onto this event; a null here is
 * a blank field on a real passenger's boarding pass.
 */
class CheckInEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(WRITE_DATES_AS_TIMESTAMPS);

    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 7, 8, 21, 25);
    private static final LocalDateTime BOARDING = LocalDateTime.of(2026, 7, 8, 20, 55);
    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 7, 8, 9, 0);

    private static CheckInEvent fullEvent(CheckInEventType type) {
        return CheckInEvent.builder()
                .type(type)
                .checkInId(501L)
                .bookingId(77L)
                .bookingReference("SB1234")
                .bookingPassengerId(42L)
                .passengerName("Praveen S")
                .contactEmail("praveen@example.com")
                .flightId(9L)
                .flightNumber("SB101")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .seatNumber("12A")
                .departureTime(DEPARTURE)
                .travelClass("BUSINESS")
                .boardingPassNumber("BP-2026-0001")
                .token("signed.jwt.token")
                .boardingTime(BOARDING)
                .boardingGroup("A")
                .gate("B12")
                .departureTerminal("5")
                .arrivalTerminal("7")
                .issuedAt(ISSUED)
                .occurredAt(ISSUED)
                .build();
    }

    @Test
    @DisplayName("the builder carries every field through to the getters")
    void builderCarriesEveryField() {
        CheckInEvent event = fullEvent(CheckInEventType.BOARDING_PASS_GENERATED);

        assertThat(event.getType()).isEqualTo(CheckInEventType.BOARDING_PASS_GENERATED);
        assertThat(event.getCheckInId()).isEqualTo(501L);
        assertThat(event.getBookingId()).isEqualTo(77L);
        assertThat(event.getBookingReference()).isEqualTo("SB1234");
        assertThat(event.getBookingPassengerId()).isEqualTo(42L);
        assertThat(event.getPassengerName()).isEqualTo("Praveen S");
        assertThat(event.getContactEmail()).isEqualTo("praveen@example.com");
        assertThat(event.getFlightId()).isEqualTo(9L);
        assertThat(event.getFlightNumber()).isEqualTo("SB101");
        assertThat(event.getOriginAirportCode()).isEqualTo("LHR");
        assertThat(event.getDestinationAirportCode()).isEqualTo("JFK");
        assertThat(event.getSeatNumber()).isEqualTo("12A");
        assertThat(event.getDepartureTime()).isEqualTo(DEPARTURE);
        assertThat(event.getTravelClass()).isEqualTo("BUSINESS");
        assertThat(event.getBoardingPassNumber()).isEqualTo("BP-2026-0001");
        assertThat(event.getToken()).isEqualTo("signed.jwt.token");
        assertThat(event.getBoardingTime()).isEqualTo(BOARDING);
        assertThat(event.getBoardingGroup()).isEqualTo("A");
        assertThat(event.getGate()).isEqualTo("B12");
        assertThat(event.getDepartureTerminal()).isEqualTo("5");
        assertThat(event.getArrivalTerminal()).isEqualTo("7");
        assertThat(event.getIssuedAt()).isEqualTo(ISSUED);
        assertThat(event.getOccurredAt()).isEqualTo(ISSUED);
    }

    @Test
    @DisplayName("the no-args constructor leaves everything null for the deserializer")
    void noArgsConstructorLeavesEverythingNull() {
        CheckInEvent event = new CheckInEvent();

        assertThat(event.getType()).isNull();
        assertThat(event.getCheckInId()).isNull();
        assertThat(event.getBoardingPassNumber()).isNull();
        assertThat(event.getToken()).isNull();
        assertThat(event.getDepartureTime()).isNull();
        assertThat(event.getOccurredAt()).isNull();
    }

    @Test
    @DisplayName("setters rewrite each field")
    void settersRewriteFields() {
        CheckInEvent event = new CheckInEvent();
        event.setType(CheckInEventType.PASSENGER_BOARDED);
        event.setSeatNumber("3C");
        event.setGate("A1");
        event.setOccurredAt(ISSUED);

        assertThat(event.getType()).isEqualTo(CheckInEventType.PASSENGER_BOARDED);
        assertThat(event.getSeatNumber()).isEqualTo("3C");
        assertThat(event.getGate()).isEqualTo("A1");
        assertThat(event.getOccurredAt()).isEqualTo(ISSUED);
    }

    @Test
    @DisplayName("a PASSENGER_CHECKED_IN event may carry no token - only the pass event signs one")
    void onlyTheBoardingPassEventCarriesAToken() {
        CheckInEvent checkedIn = CheckInEvent.builder()
                .type(CheckInEventType.PASSENGER_CHECKED_IN)
                .boardingPassNumber("BP-2026-0001")
                .build();

        assertThat(checkedIn.getBoardingPassNumber()).isEqualTo("BP-2026-0001");
        assertThat(checkedIn.getToken()).isNull();
        assertThat(fullEvent(CheckInEventType.BOARDING_PASS_GENERATED).getToken()).isNotNull();
    }

    @Test
    @DisplayName("terminals are null on a pre-terminals event rather than defaulted")
    void terminalsAreNullOnLegacyEvents() throws Exception {
        CheckInEvent event = MAPPER.readValue(
                "{\"type\":\"PASSENGER_CHECKED_IN\",\"bookingReference\":\"SB1234\",\"seatNumber\":\"12A\"}",
                CheckInEvent.class);

        assertThat(event.getType()).isEqualTo(CheckInEventType.PASSENGER_CHECKED_IN);
        assertThat(event.getSeatNumber()).isEqualTo("12A");
        assertThat(event.getDepartureTerminal()).isNull();
        assertThat(event.getArrivalTerminal()).isNull();
        assertThat(event.getBoardingGroup()).isNull();
    }

    @Test
    @DisplayName("a full event survives a JSON round trip with its timestamps intact")
    void jsonRoundTripPreservesTimestamps() throws Exception {
        CheckInEvent parsed = MAPPER.readValue(
                MAPPER.writeValueAsString(fullEvent(CheckInEventType.BOARDING_PASS_GENERATED)),
                CheckInEvent.class);

        assertThat(parsed.getDepartureTime()).isEqualTo(DEPARTURE);
        assertThat(parsed.getBoardingTime()).isEqualTo(BOARDING);
        assertThat(parsed.getIssuedAt()).isEqualTo(ISSUED);
        assertThat(parsed.getToken()).isEqualTo("signed.jwt.token");
        assertThat(parsed.getType()).isEqualTo(CheckInEventType.BOARDING_PASS_GENERATED);
    }

    @Test
    @DisplayName("boarding time precedes departure - the pass derives its clock from both")
    void boardingPrecedesDeparture() {
        CheckInEvent event = fullEvent(CheckInEventType.BOARDING_PASS_GENERATED);

        assertThat(event.getBoardingTime()).isBefore(event.getDepartureTime());
        assertThat(event.getIssuedAt()).isBefore(event.getBoardingTime());
    }

    @Test
    @DisplayName("every check-in event type can be built without any other field set")
    void everyTypeCanStandAlone() {
        for (CheckInEventType type : CheckInEventType.values()) {
            CheckInEvent event = CheckInEvent.builder().type(type).build();
            assertThat(event.getType()).isEqualTo(type);
            assertThat(event.getBookingReference()).isNull();
        }
    }
}
