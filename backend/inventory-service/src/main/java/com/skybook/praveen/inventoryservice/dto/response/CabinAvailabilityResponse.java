package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.SeatType;

/**
 * One cabin's sellable state on a flight (SEAT_SELECTION_MODULE.md §7/§11):
 * availability ONLY, deliberately no fares - base fares are booking-service's
 * FareCalculator's job and are combined with this data solely in booking's
 * /quote endpoint, so pricing rules are never duplicated here.
 */
public record CabinAvailabilityResponse(

        SeatType travelClass,

        int totalSeats,

        int availableSeats

) {
}
