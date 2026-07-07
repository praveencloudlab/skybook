package com.skybook.praveen.bookingservice.client;

/**
 * One request shape for all four inventory seat operations - their request
 * records are supersets/subsets of these fields and ignore what they don't
 * need (hold ignores reason/passenger, release ignores passenger, reserve
 * uses bookingPassengerId, cancel uses reason).
 */
public record InventorySeatCall(
        Long flightId,
        String seatNumber,
        Long bookingId,
        Long bookingPassengerId,
        String reason
) {

    public static InventorySeatCall hold(Long flightId, String seatNumber, Long bookingId) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, null, null);
    }

    public static InventorySeatCall release(Long flightId, String seatNumber, Long bookingId, String reason) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, null, reason);
    }

    public static InventorySeatCall reserve(Long flightId, String seatNumber, Long bookingId, Long bookingPassengerId) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, bookingPassengerId, null);
    }
}
