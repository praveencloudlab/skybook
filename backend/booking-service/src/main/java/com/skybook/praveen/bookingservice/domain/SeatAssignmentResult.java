package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;

import java.math.BigDecimal;

/**
 * The outcome of one passenger's inventory hold (SEAT_SELECTION_MODULE.md
 * §5.1), carried from the facade's hold step into
 * {@code finalizeSeatAssignments}. Values come from the hold response -
 * inventory's persisted snapshot is the pricing authority; booking never
 * recomputes them.
 *
 * seatNumber may be null only on the no-inventory path (flight without a
 * FlightInventory record - the pre-existing hold-if-exists policy), where
 * chargedSurcharge is 0 because there is no pricing authority to consult.
 */
public record SeatAssignmentResult(
        Long bookingPassengerId,
        String seatNumber,
        BigDecimal listedSurcharge,
        BigDecimal chargedSurcharge,
        SeatAssignmentMode mode
) {
}
