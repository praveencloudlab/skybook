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
        // baseFare + seatSurcharge + baggageFee. seatSurcharge is what was
        // actually charged (0 for an AUTO seat), not the seat's listed price.
        BigDecimal baseFare,

        BigDecimal seatSurcharge,

        // Ancillary bags bought at booking, and what they actually cost.
        int extraBags,

        BigDecimal baggageFee,

        SeatAssignmentMode chargedSeatAssignmentMode,

        String currency,

        BigDecimal fare,

        CheckInStatus checkInStatus,

        // Passenger-level cancellation (business rules): true once this traveller
        // is cancelled off the booking. The booking survives until all are.
        boolean cancelled,

        // ADULT / CHILD / INFANT, derived from date of birth - drives the
        // guardian rule (a minor can't remain without an adult).
        String passengerType

) {
}
