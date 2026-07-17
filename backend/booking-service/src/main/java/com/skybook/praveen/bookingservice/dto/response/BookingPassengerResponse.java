package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;

import java.math.BigDecimal;

public record BookingPassengerResponse(

        // This is BookingPassenger.id - the identifier used in
        // /bookings/{id}/passengers/{passengerId}/... routes.
        Long id,

        Long passengerId,

        String firstName,

        String lastName,

        String passportNumber,

        TravelClass travelClass,

        FareType fareType,

        String seatNumber,

        // Fare breakdown (SEAT_SELECTION_MODULE.md §8): the all-in `fare` is
        // baseFare + seatSurcharge. seatSurcharge is what was actually charged
        // (0 for an AUTO seat), not the seat's listed price.
        BigDecimal baseFare,

        BigDecimal seatSurcharge,

        SeatAssignmentMode chargedSeatAssignmentMode,

        String currency,

        BigDecimal fare,

        CheckInStatus checkInStatus

) {
}
