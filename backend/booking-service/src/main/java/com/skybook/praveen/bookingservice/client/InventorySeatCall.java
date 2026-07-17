package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.enums.TravelClass;

/**
 * One request shape for all five inventory seat operations - their request
 * records are supersets/subsets of these fields and ignore what they don't
 * need (release ignores passenger/class, reserve ignores class/reason,
 * cancel uses reason). TravelClass serializes by name, which matches
 * inventory's SeatType 1:1 by design.
 */
public record InventorySeatCall(
        Long flightId,
        String seatNumber,
        Long bookingId,
        Long bookingPassengerId,
        TravelClass travelClass,
        String reason
) {

    /** Manual hold (§6): inventory validates the cabin and prices the seat. */
    public static InventorySeatCall hold(Long flightId, String seatNumber, Long bookingId,
                                         Long bookingPassengerId, TravelClass travelClass) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, bookingPassengerId, travelClass, null);
    }

    /** Atomic auto-hold (§5.2): no seat - inventory picks; flightId travels in the path. */
    public static InventorySeatCall autoHold(Long bookingId, Long bookingPassengerId, TravelClass travelClass) {
        return new InventorySeatCall(null, null, bookingId, bookingPassengerId, travelClass, null);
    }

    public static InventorySeatCall release(Long flightId, String seatNumber, Long bookingId, String reason) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, null, null, reason);
    }

    public static InventorySeatCall reserve(Long flightId, String seatNumber, Long bookingId, Long bookingPassengerId) {
        return new InventorySeatCall(flightId, seatNumber, bookingId, bookingPassengerId, null, null);
    }
}
