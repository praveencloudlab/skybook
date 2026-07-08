package com.skybook.praveen.checkinservice.client;

/**
 * One request shape for the reservation operations checkin-service needs -
 * same superset-of-fields pattern as booking-service's InventorySeatCall.
 * checkin-service never holds seats (only booking-service does, at booking
 * creation), so this only covers reserve (direct, no holdId) and cancel.
 */
public record InventorySeatCall(
        Long flightId,
        String seatNumber,
        Long bookingId,
        Long bookingPassengerId,
        String reason
) {

    public static InventorySeatCall reserve(Long flightId, String seatNumber, Long bookingId, Long bookingPassengerId) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, bookingPassengerId, null);
    }

    public static InventorySeatCall cancel(Long flightId, String seatNumber, Long bookingId, String reason) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, null, reason);
    }
}
