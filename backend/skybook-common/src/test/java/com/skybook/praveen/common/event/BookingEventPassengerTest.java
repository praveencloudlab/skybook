package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One traveller row inside a BookingEvent. Nearly every field here was added
 * after the fact for a different consumer (bookingPassengerId for check-in,
 * seatSurcharge for the free-seat-change ceiling, ticketNumber for coupons),
 * so "null means the producer predates this field" is the contract, not a
 * defect.
 */
class BookingEventPassengerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the builder carries every field through to the getters")
    void builderCarriesEveryField() {
        BookingEventPassenger passenger = BookingEventPassenger.builder()
                .bookingPassengerId(42L)
                .segmentIndex(1)
                .ticketNumber("1251234567890")
                .name("Praveen S")
                .seatNumber("12A")
                .travelClass("BUSINESS")
                .fareType("FLEXI")
                .fare(new BigDecimal("450.00"))
                .seatSurcharge(new BigDecimal("25.00"))
                .currency("USD")
                .checkInStatus("CHECKED_IN")
                .build();

        assertThat(passenger.getBookingPassengerId()).isEqualTo(42L);
        assertThat(passenger.getSegmentIndex()).isEqualTo(1);
        assertThat(passenger.getTicketNumber()).isEqualTo("1251234567890");
        assertThat(passenger.getName()).isEqualTo("Praveen S");
        assertThat(passenger.getSeatNumber()).isEqualTo("12A");
        assertThat(passenger.getTravelClass()).isEqualTo("BUSINESS");
        assertThat(passenger.getFareType()).isEqualTo("FLEXI");
        assertThat(passenger.getFare()).isEqualByComparingTo("450.00");
        assertThat(passenger.getSeatSurcharge()).isEqualByComparingTo("25.00");
        assertThat(passenger.getCurrency()).isEqualTo("USD");
        assertThat(passenger.getCheckInStatus()).isEqualTo("CHECKED_IN");
    }

    @Test
    @DisplayName("the all-args constructor matches the declared field order")
    void allArgsConstructorMatchesFieldOrder() {
        BookingEventPassenger passenger = new BookingEventPassenger(
                42L, 0, "1251234567890", "Praveen S", "12A",
                "ECONOMY", "SAVER", new BigDecimal("120.00"), BigDecimal.ZERO, "USD", "NOT_OPEN");

        assertThat(passenger.getBookingPassengerId()).isEqualTo(42L);
        assertThat(passenger.getSegmentIndex()).isZero();
        assertThat(passenger.getName()).isEqualTo("Praveen S");
        assertThat(passenger.getTravelClass()).isEqualTo("ECONOMY");
        assertThat(passenger.getFareType()).isEqualTo("SAVER");
        assertThat(passenger.getSeatSurcharge()).isEqualByComparingTo("0");
        assertThat(passenger.getCheckInStatus()).isEqualTo("NOT_OPEN");
    }

    @Test
    @DisplayName("the no-args constructor leaves everything null for the deserializer to fill")
    void noArgsConstructorLeavesEverythingNull() {
        BookingEventPassenger passenger = new BookingEventPassenger();

        assertThat(passenger.getBookingPassengerId()).isNull();
        assertThat(passenger.getSegmentIndex()).isNull();
        assertThat(passenger.getTicketNumber()).isNull();
        assertThat(passenger.getName()).isNull();
        assertThat(passenger.getSeatNumber()).isNull();
        assertThat(passenger.getFare()).isNull();
        assertThat(passenger.getSeatSurcharge()).isNull();
        assertThat(passenger.getCheckInStatus()).isNull();
    }

    @Test
    @DisplayName("setters rewrite each field - consumers enrich rows before re-publishing")
    void settersRewriteFields() {
        BookingEventPassenger passenger = new BookingEventPassenger();
        passenger.setName("Ann");
        passenger.setSeatNumber("3C");
        passenger.setCheckInStatus("CHECKED_IN");
        passenger.setSeatSurcharge(new BigDecimal("15.00"));

        assertThat(passenger.getName()).isEqualTo("Ann");
        assertThat(passenger.getSeatNumber()).isEqualTo("3C");
        assertThat(passenger.getCheckInStatus()).isEqualTo("CHECKED_IN");
        assertThat(passenger.getSeatSurcharge()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("a pre-enrichment row is legal: no id, no segment index, no surcharge")
    void preEnrichmentRowIsLegal() throws Exception {
        BookingEventPassenger passenger =
                MAPPER.readValue("{\"name\":\"Ann\",\"seatNumber\":\"12A\"}", BookingEventPassenger.class);

        assertThat(passenger.getName()).isEqualTo("Ann");
        assertThat(passenger.getSeatNumber()).isEqualTo("12A");
        // Null, NOT zero/defaulted - checkin-service skips these rows loudly.
        assertThat(passenger.getBookingPassengerId()).isNull();
        assertThat(passenger.getSegmentIndex()).isNull();
        assertThat(passenger.getSeatSurcharge()).isNull();
        assertThat(passenger.getTicketNumber()).isNull();
    }

    @Test
    @DisplayName("an AUTO-assigned seat carries a zero surcharge, which is not the same as null")
    void zeroSurchargeIsDistinctFromNull() {
        BookingEventPassenger auto = BookingEventPassenger.builder().seatSurcharge(BigDecimal.ZERO).build();
        BookingEventPassenger legacy = BookingEventPassenger.builder().build();

        assertThat(auto.getSeatSurcharge()).isNotNull().isEqualByComparingTo("0");
        assertThat(legacy.getSeatSurcharge()).isNull();
    }

    @Test
    @DisplayName("segment index 0 is the outbound leg and 1 the return")
    void segmentIndexDistinguishesTheLegs() {
        assertThat(BookingEventPassenger.builder().segmentIndex(0).build().getSegmentIndex()).isZero();
        assertThat(BookingEventPassenger.builder().segmentIndex(1).build().getSegmentIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("a fully populated row survives a JSON round trip")
    void jsonRoundTripPreservesEveryField() throws Exception {
        BookingEventPassenger original = BookingEventPassenger.builder()
                .bookingPassengerId(42L)
                .segmentIndex(1)
                .ticketNumber("1251234567890")
                .name("Praveen S")
                .seatNumber("12A")
                .travelClass("BUSINESS")
                .fareType("FLEXI")
                .fare(new BigDecimal("450.00"))
                .seatSurcharge(new BigDecimal("25.00"))
                .currency("USD")
                .checkInStatus("CHECKED_IN")
                .build();

        BookingEventPassenger parsed =
                MAPPER.readValue(MAPPER.writeValueAsString(original), BookingEventPassenger.class);

        assertThat(parsed.getBookingPassengerId()).isEqualTo(42L);
        assertThat(parsed.getTicketNumber()).isEqualTo("1251234567890");
        assertThat(parsed.getFare()).isEqualByComparingTo("450.00");
        assertThat(parsed.getSeatSurcharge()).isEqualByComparingTo("25.00");
        assertThat(parsed.getCheckInStatus()).isEqualTo("CHECKED_IN");
    }
}
