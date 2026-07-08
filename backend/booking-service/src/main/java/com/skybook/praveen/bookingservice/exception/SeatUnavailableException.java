package com.skybook.praveen.bookingservice.exception;

/**
 * The seat cannot be taken according to inventory-service - held by another
 * booking, already reserved, or absent from the seat map. Distinct from
 * SeatAlreadyBookedException, which is booking-service's own local
 * duplicate-seat check within its bookings table.
 */
public class SeatUnavailableException extends RuntimeException {

    public SeatUnavailableException(Long flightId, String seatNumber, String reason) {
        super("Seat " + seatNumber + " on flight " + flightId + " is unavailable: " + reason);
    }
}
