package com.skybook.praveen.checkinservice.exception;

public class BoardingPassNotFoundException extends RuntimeException {

    private BoardingPassNotFoundException(String message) {
        super(message);
    }

    public static BoardingPassNotFoundException byId(Long id) {
        return new BoardingPassNotFoundException("Boarding pass not found with id: " + id);
    }

    public static BoardingPassNotFoundException byCheckIn(Long checkInId) {
        return new BoardingPassNotFoundException(
                "No active boarding pass for check-in id: " + checkInId + " (not checked in yet?)");
    }
}
