package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
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

        BigDecimal fare,

        CheckInStatus checkInStatus

) {
}
