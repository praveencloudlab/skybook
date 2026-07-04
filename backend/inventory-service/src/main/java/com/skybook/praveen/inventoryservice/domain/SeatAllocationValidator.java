package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import org.springframework.stereotype.Component;

/**
 * Guard-clause twin of SeatAvailabilityChecker: where the checker answers
 * true/false for search-style flows, this validator throws with a precise
 * reason for command flows (hold/reserve). Exceptions are IllegalState for
 * now; the service layer wraps them into the dedicated exception types
 * (SeatNotAvailableException etc.) once those exist per the build order.
 */
@Component
public class SeatAllocationValidator {

    public void validateInventoryOpen(FlightInventory inventory) {
        if (inventory.getAircraft().getStatus() != AircraftStatus.ACTIVE) {
            throw new IllegalStateException("Aircraft " + inventory.getAircraft().getRegistrationNumber()
                    + " is " + inventory.getAircraft().getStatus() + " - inventory not sellable");
        }
        if (inventory.getStatus() != InventoryStatus.OPEN) {
            throw new IllegalStateException("Inventory for flight " + inventory.getFlightId()
                    + " is " + inventory.getStatus());
        }
    }

    public void validateSeatUsable(AircraftSeat seat) {
        if (seat.getStatus() != AircraftSeatStatus.ACTIVE) {
            throw new IllegalStateException("Seat " + seat.getSeatNumber()
                    + " is " + seat.getStatus() + " - not sellable");
        }
    }

    /**
     * Exit-row restriction hook. Passenger attributes (age, mobility) live in
     * booking-service; until that data crosses the wire the flag simply has
     * to be asserted by the caller.
     */
    public void validateExitRowAllowed(AircraftSeat seat, boolean passengerEligibleForExitRow) {
        if (Boolean.TRUE.equals(seat.getExitRow()) && !passengerEligibleForExitRow) {
            throw new IllegalStateException("Seat " + seat.getSeatNumber()
                    + " is an exit-row seat - passenger not eligible");
        }
    }
}
