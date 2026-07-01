package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import org.springframework.stereotype.Component;

/**
 * v1 implementation - the passenger (or the client on their behalf) must
 * supply a seat number; there's no auto-assignment yet. See
 * {@link SeatAssignmentStrategy}.
 */
@Component
public class ManualSeatAssignmentStrategy implements SeatAssignmentStrategy {

    @Override
    public String resolveSeatNumber(BookingPassenger passenger, String requestedSeatNumber) {

        if (requestedSeatNumber == null || requestedSeatNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "Seat number is required - automatic seat assignment isn't implemented yet");
        }

        return requestedSeatNumber.toUpperCase();
    }
}
