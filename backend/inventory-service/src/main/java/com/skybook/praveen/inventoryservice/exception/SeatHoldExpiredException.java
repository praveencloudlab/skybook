package com.skybook.praveen.inventoryservice.exception;

public class SeatHoldExpiredException extends RuntimeException {

    public SeatHoldExpiredException(Long holdId) {
        super("Seat hold " + holdId + " has expired");
    }
}
