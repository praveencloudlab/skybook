package com.skybook.praveen.checkinservice.exception;

public class CheckInNotFoundException extends RuntimeException {

    private CheckInNotFoundException(String message) {
        super(message);
    }

    public static CheckInNotFoundException byId(Long id) {
        return new CheckInNotFoundException("Check-in not found with id: " + id);
    }
}
