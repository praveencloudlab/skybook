package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The @PrePersist defaults exist so a row written by ANY path - including one
 * predating the fare breakdown - lands consistent: the persisted breakdown is
 * what refunds, invoices and check-in seat-change ceilings are computed from
 * later, so a null there is real money going wrong months after the booking.
 */
class BookingPassengerTest {

    private static BookingPassenger.BookingPassengerBuilder row() {
        return BookingPassenger.builder()
                .id(11L)
                .flightId(9L)
                .travelClass(TravelClass.ECONOMY)
                .fareType(FareType.SAVER)
                .fare(new BigDecimal("142.00"));
    }

    @Nested
    @DisplayName("defaults filled in on persist")
    class PrePersistDefaults {

        @Test
        @DisplayName("a row with only a total fare is back-filled into a complete breakdown")
        void aBareRowIsBackFilledIntoACompleteBreakdown() {
            BookingPassenger passenger = row().build();

            passenger.prePersist();

            assertThat(passenger.getCheckInStatus()).isEqualTo(CheckInStatus.NOT_OPEN);
            assertThat(passenger.getSeatSurcharge()).isEqualByComparingTo("0");
            assertThat(passenger.getBaggageFee()).isEqualByComparingTo("0");
            assertThat(passenger.getChargedSeatAssignmentMode()).isEqualTo(SeatAssignmentMode.MANUAL);
            assertThat(passenger.getCurrency()).isEqualTo("GBP");
            // With no surcharge and no bags, the base fare IS the total.
            assertThat(passenger.getBaseFare()).isEqualByComparingTo("142.00");
        }

        @Test
        @DisplayName("values the booking flow set explicitly are never overwritten")
        void explicitValuesAreNeverOverwritten() {
            BookingPassenger passenger = row()
                    .baseFare(new BigDecimal("100.00"))
                    .seatSurcharge(new BigDecimal("12.00"))
                    .baggageFee(new BigDecimal("30.00"))
                    .extraBags(1)
                    .chargedSeatAssignmentMode(SeatAssignmentMode.AUTO)
                    .currency("USD")
                    .checkInStatus(CheckInStatus.CHECKED_IN)
                    .build();

            passenger.prePersist();

            assertThat(passenger.getBaseFare()).isEqualByComparingTo("100.00");
            assertThat(passenger.getSeatSurcharge()).isEqualByComparingTo("12.00");
            assertThat(passenger.getBaggageFee()).isEqualByComparingTo("30.00");
            assertThat(passenger.getChargedSeatAssignmentMode()).isEqualTo(SeatAssignmentMode.AUTO);
            assertThat(passenger.getCurrency()).isEqualTo("USD");
            assertThat(passenger.getCheckInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
        }

        @Test
        @DisplayName("an AUTO seat keeps its zero surcharge - the default must not turn it MANUAL")
        void anAutoSeatKeepsItsZeroSurcharge() {
            BookingPassenger passenger = row()
                    .seatSurcharge(BigDecimal.ZERO)
                    .chargedSeatAssignmentMode(SeatAssignmentMode.AUTO)
                    .build();

            passenger.prePersist();

            assertThat(passenger.getChargedSeatAssignmentMode()).isEqualTo(SeatAssignmentMode.AUTO);
            assertThat(passenger.getSeatSurcharge()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("running the defaults twice changes nothing - re-persisting a row is safe")
        void runningTheDefaultsTwiceChangesNothing() {
            BookingPassenger passenger = row().build();

            passenger.prePersist();
            passenger.setFare(new BigDecimal("999.00"));
            passenger.prePersist();

            // baseFare was already back-filled, so the second pass must not chase the new total.
            assertThat(passenger.getBaseFare()).isEqualByComparingTo("142.00");
            assertThat(passenger.getCurrency()).isEqualTo("GBP");
        }

        @Test
        @DisplayName("a fareless draft row is tolerated - baseFare simply stays null")
        void aFarelessDraftRowIsTolerated() {
            BookingPassenger draft = BookingPassenger.builder()
                    .flightId(9L)
                    .travelClass(TravelClass.ECONOMY)
                    .fareType(FareType.SAVER)
                    .build();

            draft.prePersist();

            assertThat(draft.getBaseFare()).isNull();
            assertThat(draft.getCheckInStatus()).isEqualTo(CheckInStatus.NOT_OPEN);
            assertThat(draft.getSeatSurcharge()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("the row as a per-segment line item")
    class PerSegmentLineItem {

        @Test
        @DisplayName("the row carries its own segment, flight and seat - never the booking's")
        void theRowCarriesItsOwnSegmentAndFlight() {
            BookingSegment segment = BookingSegment.builder().id(2L).segmentIndex(1).flightId(10L).build();
            Booking booking = Booking.builder().id(77L).bookingReference("SB1234").build();
            Passenger traveller = Passenger.builder().id(500L).firstName("Ann").lastName("Blake").build();

            BookingPassenger passenger = row()
                    .booking(booking)
                    .passenger(traveller)
                    .segment(segment)
                    .flightId(10L)
                    .seatNumber("14C")
                    .build();

            assertThat(passenger.getSegment().getSegmentIndex()).isEqualTo(1);
            assertThat(passenger.getFlightId()).isEqualTo(10L);
            assertThat(passenger.getSeatNumber()).isEqualTo("14C");
            assertThat(passenger.getBooking().getBookingReference()).isEqualTo("SB1234");
            assertThat(passenger.getPassenger().getFirstName()).isEqualTo("Ann");
        }

        @Test
        @DisplayName("a new row is not cancelled - passenger cancellation is an explicit act")
        void aNewRowIsNotCancelled() {
            BookingPassenger passenger = row().build();

            assertThat(passenger.isCancelled()).isFalse();

            passenger.setCancelled(true);
            assertThat(passenger.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("the check-in mirror is writable - the Kafka consumer updates it in place")
        void theCheckInMirrorIsWritable() {
            BookingPassenger passenger = row().checkInStatus(CheckInStatus.NOT_OPEN).build();

            passenger.setCheckInStatus(CheckInStatus.CHECKED_IN);
            passenger.setSeatNumber("20F");

            assertThat(passenger.getCheckInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
            assertThat(passenger.getSeatNumber()).isEqualTo("20F");
        }
    }
}
