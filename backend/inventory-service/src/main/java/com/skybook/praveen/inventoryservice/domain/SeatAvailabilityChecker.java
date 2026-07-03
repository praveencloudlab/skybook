package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import org.springframework.stereotype.Component;

/**
 * Pure availability rules - answers "could this seat be sold on this flight
 * right now?" from state alone. The caller supplies the hold/reservation
 * occupancy flags (repository lookups happen in the service layer) so this
 * class stays dependency-free and unit-testable without mocks.
 */
@Component
public class SeatAvailabilityChecker {

    /** Inventory-level gate: is this flight selling at all? */
    public boolean isInventoryOpen(FlightInventory inventory) {
        return inventory.getStatus() == InventoryStatus.OPEN
                && inventory.getAircraft().getStatus() == AircraftStatus.ACTIVE;
    }

    /** Seat-level gate: is the physical seat usable, independent of occupancy? */
    public boolean isSeatUsable(AircraftSeat seat) {
        return seat.getStatus() == AircraftSeatStatus.ACTIVE;
    }

    /**
     * Full check. hasActiveHold / hasActiveReservation reflect existing
     * ACTIVE/RESERVED rows for this seat on this flight, looked up by the
     * caller.
     */
    public boolean isSeatAvailable(FlightInventory inventory, AircraftSeat seat,
                                   boolean hasActiveHold, boolean hasActiveReservation) {
        return isInventoryOpen(inventory)
                && isSeatUsable(seat)
                && !hasActiveHold
                && !hasActiveReservation
                && inventory.getAvailableSeats() > 0;
    }
}
