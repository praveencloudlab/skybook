package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * inventory-service's seat lifecycle on skybook.inventory.events. Seat-level
 * events name a seat and the booking that moved it; inventory-level events
 * (INVENTORY_CREATED) leave both null on purpose.
 */
class InventoryEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the builder carries every field through to the getters")
    void builderCarriesEveryField() {
        InventoryEvent event = InventoryEvent.builder()
                .type(InventoryEventType.SEAT_HELD)
                .flightId(9L)
                .seatNumber("12A")
                .bookingId(77L)
                .details("held for 15 minutes")
                .build();

        assertThat(event.getType()).isEqualTo(InventoryEventType.SEAT_HELD);
        assertThat(event.getFlightId()).isEqualTo(9L);
        assertThat(event.getSeatNumber()).isEqualTo("12A");
        assertThat(event.getBookingId()).isEqualTo(77L);
        assertThat(event.getDetails()).isEqualTo("held for 15 minutes");
    }

    @Test
    @DisplayName("the all-args constructor matches the declared field order")
    void allArgsConstructorMatchesFieldOrder() {
        InventoryEvent event = new InventoryEvent(
                InventoryEventType.SEAT_RELEASED, 9L, "12A", 77L, "hold expired");

        assertThat(event.getType()).isEqualTo(InventoryEventType.SEAT_RELEASED);
        assertThat(event.getFlightId()).isEqualTo(9L);
        assertThat(event.getSeatNumber()).isEqualTo("12A");
        assertThat(event.getBookingId()).isEqualTo(77L);
        assertThat(event.getDetails()).isEqualTo("hold expired");
    }

    @Test
    @DisplayName("the no-args constructor leaves everything null for the deserializer")
    void noArgsConstructorLeavesEverythingNull() {
        InventoryEvent event = new InventoryEvent();

        assertThat(event.getType()).isNull();
        assertThat(event.getFlightId()).isNull();
        assertThat(event.getSeatNumber()).isNull();
        assertThat(event.getBookingId()).isNull();
        assertThat(event.getDetails()).isNull();
    }

    @Test
    @DisplayName("setters rewrite each field")
    void settersRewriteFields() {
        InventoryEvent event = new InventoryEvent();
        event.setType(InventoryEventType.HOLD_EXPIRED);
        event.setFlightId(10L);
        event.setSeatNumber("3C");
        event.setBookingId(78L);
        event.setDetails("swept");

        assertThat(event.getType()).isEqualTo(InventoryEventType.HOLD_EXPIRED);
        assertThat(event.getFlightId()).isEqualTo(10L);
        assertThat(event.getSeatNumber()).isEqualTo("3C");
        assertThat(event.getBookingId()).isEqualTo(78L);
        assertThat(event.getDetails()).isEqualTo("swept");
    }

    @Test
    @DisplayName("an inventory-level event legitimately has no seat and no booking")
    void inventoryLevelEventHasNoSeatOrBooking() {
        InventoryEvent event = InventoryEvent.builder()
                .type(InventoryEventType.INVENTORY_CREATED)
                .flightId(9L)
                .details("180 seats seeded")
                .build();

        assertThat(event.getFlightId()).isEqualTo(9L);
        assertThat(event.getSeatNumber()).isNull();
        assertThat(event.getBookingId()).isNull();
    }

    @Test
    @DisplayName("a seat event survives a JSON round trip")
    void jsonRoundTripPreservesEveryField() throws Exception {
        InventoryEvent original = InventoryEvent.builder()
                .type(InventoryEventType.SEAT_RESERVED)
                .flightId(9L)
                .seatNumber("12A")
                .bookingId(77L)
                .details("confirmed at payment")
                .build();

        InventoryEvent parsed = MAPPER.readValue(MAPPER.writeValueAsString(original), InventoryEvent.class);

        assertThat(parsed.getType()).isEqualTo(InventoryEventType.SEAT_RESERVED);
        assertThat(parsed.getFlightId()).isEqualTo(9L);
        assertThat(parsed.getSeatNumber()).isEqualTo("12A");
        assertThat(parsed.getBookingId()).isEqualTo(77L);
        assertThat(parsed.getDetails()).isEqualTo("confirmed at payment");
    }

    @Test
    @DisplayName("every inventory event type can be built without any other field set")
    void everyTypeCanStandAlone() {
        for (InventoryEventType type : InventoryEventType.values()) {
            assertThat(InventoryEvent.builder().type(type).build().getType()).isEqualTo(type);
        }
    }
}
