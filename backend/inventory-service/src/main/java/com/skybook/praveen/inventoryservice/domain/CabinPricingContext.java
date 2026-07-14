package com.skybook.praveen.inventoryservice.domain;

/**
 * Cabin context for seat pricing (SEAT_SELECTION_MODULE.md §4, review round 3):
 * "front of cabin" is relative to the CABIN's first row, never the global
 * aircraft row number - Business row 3 on a 777 is that cabin's front row even
 * though it's the aircraft's third. A seat's own attributes can't tell the
 * pricing policy where its cabin starts, so callers derive this per
 * (aircraft, seatType) from the seat map and pass it in.
 *
 * @param firstCabinRow lowest rowNumber among seats of the cabin's seatType
 * @param frontRowCount how many rows from firstCabinRow count as front-of-cabin
 */
public record CabinPricingContext(int firstCabinRow, int frontRowCount) {

    public boolean isFrontOfCabin(int rowNumber) {
        return rowNumber < firstCabinRow + frontRowCount;
    }
}
