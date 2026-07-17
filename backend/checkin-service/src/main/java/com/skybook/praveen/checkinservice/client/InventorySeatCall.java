package com.skybook.praveen.checkinservice.client;

import java.math.BigDecimal;

/**
 * One request shape for the reservation operations checkin-service needs -
 * same superset-of-fields pattern as booking-service's InventorySeatCall.
 * checkin-service never holds seats (only booking-service does, at booking
 * creation), so this only covers reserve (direct, no holdId) and cancel.
 *
 * travelClass + maxAllowedSurcharge are REQUIRED on the check-in reserve
 * (SEAT_SELECTION_MODULE.md §9, round-7 contract): inventory authoritatively
 * enforces cabin match and listedSurcharge <= ceiling under the flight lock.
 */
public record InventorySeatCall(
        Long flightId,
        String seatNumber,
        Long bookingId,
        Long bookingPassengerId,
        String travelClass,
        BigDecimal maxAllowedSurcharge,
        String reason
) {

    public static InventorySeatCall reserve(Long flightId, String seatNumber, Long bookingId,
                                            Long bookingPassengerId, String travelClass,
                                            BigDecimal maxAllowedSurcharge) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, bookingPassengerId,
                travelClass, maxAllowedSurcharge, null);
    }

    public static InventorySeatCall cancel(Long flightId, String seatNumber, Long bookingId, String reason) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, null, null, null, reason);
    }
}
