package com.skybook.praveen.checkinservice.exception;

public class BoardingPassNotFoundException extends RuntimeException {

    private BoardingPassNotFoundException(String message) {
        super(message);
    }

    public static BoardingPassNotFoundException byId(Long id) {
        return new BoardingPassNotFoundException("Boarding pass not found with id: " + id);
    }
}
