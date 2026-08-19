package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployment rule for this fleet is that every consumer must know a new
 * enum constant BEFORE any producer emits it - an unknown name blows up
 * deserialization on the consumer side and parks the message in the DLT. The
 * value sets are therefore pinned here in declaration order: adding a constant
 * fails this suite, which is the reminder to deploy consumers first.
 */
class EventEnumsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("BookingEventType")
    class BookingEventTypes {

        @Test
        @DisplayName("holds exactly the seven published booking event types, in order")
        void valuesAreExact() {
            assertThat(BookingEventType.values()).containsExactly(
                    BookingEventType.CREATED,
                    BookingEventType.CONFIRMED,
                    BookingEventType.CANCELLED,
                    BookingEventType.PARTIALLY_CANCELLED,
                    BookingEventType.EXPIRED,
                    BookingEventType.COMPLETED,
                    BookingEventType.FARE_ALERT);
        }

        @Test
        @DisplayName("every constant round trips through valueOf and name")
        void valueOfRoundTrips() {
            for (BookingEventType type : BookingEventType.values()) {
                assertThat(BookingEventType.valueOf(type.name())).isSameAs(type);
            }
        }

        @Test
        @DisplayName("an unknown constant name is rejected - this is the DLT case")
        void unknownNameIsRejected() {
            assertThatThrownBy(() -> BookingEventType.valueOf("REBOOKED"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("serializes by name, so JSON on the wire carries the constant verbatim")
        void serializesByName() throws Exception {
            assertThat(MAPPER.writeValueAsString(BookingEventType.PARTIALLY_CANCELLED))
                    .isEqualTo("\"PARTIALLY_CANCELLED\"");
            assertThat(MAPPER.readValue("\"FARE_ALERT\"", BookingEventType.class))
                    .isSameAs(BookingEventType.FARE_ALERT);
        }
    }

    @Nested
    @DisplayName("CheckInEventType")
    class CheckInEventTypes {

        @Test
        @DisplayName("holds exactly the five check-in lifecycle types, in order")
        void valuesAreExact() {
            assertThat(CheckInEventType.values()).containsExactly(
                    CheckInEventType.PASSENGER_CHECKED_IN,
                    CheckInEventType.BOARDING_PASS_GENERATED,
                    CheckInEventType.PASSENGER_BOARDED,
                    CheckInEventType.PASSENGER_NO_SHOW,
                    CheckInEventType.PASSENGER_CHECKIN_CANCELLED);
        }

        @Test
        @DisplayName("every constant round trips through valueOf and name")
        void valueOfRoundTrips() {
            for (CheckInEventType type : CheckInEventType.values()) {
                assertThat(CheckInEventType.valueOf(type.name())).isSameAs(type);
            }
        }

        @Test
        @DisplayName("an unknown constant name is rejected")
        void unknownNameIsRejected() {
            assertThatThrownBy(() -> CheckInEventType.valueOf("PASSENGER_OFFLOADED"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PaymentEventType")
    class PaymentEventTypes {

        @Test
        @DisplayName("holds exactly the five payment outcomes, in order")
        void valuesAreExact() {
            assertThat(PaymentEventType.values()).containsExactly(
                    PaymentEventType.PAYMENT_SUCCEEDED,
                    PaymentEventType.PAYMENT_FAILED,
                    PaymentEventType.PAYMENT_CANCELLED,
                    PaymentEventType.REFUND_COMPLETED,
                    PaymentEventType.REFUND_FAILED);
        }

        @Test
        @DisplayName("every constant round trips through valueOf and name")
        void valueOfRoundTrips() {
            for (PaymentEventType type : PaymentEventType.values()) {
                assertThat(PaymentEventType.valueOf(type.name())).isSameAs(type);
            }
        }

        @Test
        @DisplayName("an unknown constant name is rejected")
        void unknownNameIsRejected() {
            assertThatThrownBy(() -> PaymentEventType.valueOf("CHARGEBACK"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("InventoryEventType")
    class InventoryEventTypes {

        @Test
        @DisplayName("holds exactly the six seat-lifecycle types, in order")
        void valuesAreExact() {
            assertThat(InventoryEventType.values()).containsExactly(
                    InventoryEventType.INVENTORY_CREATED,
                    InventoryEventType.SEAT_HELD,
                    InventoryEventType.SEAT_RELEASED,
                    InventoryEventType.HOLD_EXPIRED,
                    InventoryEventType.SEAT_RESERVED,
                    InventoryEventType.RESERVATION_CANCELLED);
        }

        @Test
        @DisplayName("every constant round trips through valueOf and name")
        void valueOfRoundTrips() {
            for (InventoryEventType type : InventoryEventType.values()) {
                assertThat(InventoryEventType.valueOf(type.name())).isSameAs(type);
            }
        }

        @Test
        @DisplayName("an unknown constant name is rejected")
        void unknownNameIsRejected() {
            assertThatThrownBy(() -> InventoryEventType.valueOf("SEAT_SWAPPED"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("EmailType")
    class EmailTypes {

        @Test
        @DisplayName("holds exactly the three transactional mail types, in order")
        void valuesAreExact() {
            assertThat(EmailType.values()).containsExactly(
                    EmailType.REGISTRATION_SUCCESS,
                    EmailType.FORGOT_PASSWORD,
                    EmailType.EMAIL_VERIFICATION);
        }

        @Test
        @DisplayName("every constant round trips through valueOf and name")
        void valueOfRoundTrips() {
            for (EmailType type : EmailType.values()) {
                assertThat(EmailType.valueOf(type.name())).isSameAs(type);
            }
        }

        @Test
        @DisplayName("an unknown constant name is rejected")
        void unknownNameIsRejected() {
            assertThatThrownBy(() -> EmailType.valueOf("BOOKING_CONFIRMED"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("across all five enums")
    class SharedContract {

        @Test
        @DisplayName("each enum's first constant sits at ordinal 0 - ordinals must not drift")
        void ordinalsAreStableFromZero() {
            assertThat(BookingEventType.CREATED.ordinal()).isZero();
            assertThat(CheckInEventType.PASSENGER_CHECKED_IN.ordinal()).isZero();
            assertThat(PaymentEventType.PAYMENT_SUCCEEDED.ordinal()).isZero();
            assertThat(InventoryEventType.INVENTORY_CREATED.ordinal()).isZero();
            assertThat(EmailType.REGISTRATION_SUCCESS.ordinal()).isZero();
        }

        @Test
        @DisplayName("the enum sizes are pinned, so an accidental addition is caught here first")
        void sizesArePinned() {
            assertThat(BookingEventType.values()).hasSize(7);
            assertThat(CheckInEventType.values()).hasSize(5);
            assertThat(PaymentEventType.values()).hasSize(5);
            assertThat(InventoryEventType.values()).hasSize(6);
            assertThat(EmailType.values()).hasSize(3);
        }

        @Test
        @DisplayName("every constant name is SCREAMING_SNAKE_CASE - the wire format is the name")
        void namesFollowTheWireConvention() {
            assertThat(BookingEventType.values()).allSatisfy(t -> assertThat(t.name()).matches("[A-Z_]+"));
            assertThat(CheckInEventType.values()).allSatisfy(t -> assertThat(t.name()).matches("[A-Z_]+"));
            assertThat(PaymentEventType.values()).allSatisfy(t -> assertThat(t.name()).matches("[A-Z_]+"));
            assertThat(InventoryEventType.values()).allSatisfy(t -> assertThat(t.name()).matches("[A-Z_]+"));
            assertThat(EmailType.values()).allSatisfy(t -> assertThat(t.name()).matches("[A-Z_]+"));
        }
    }
}
