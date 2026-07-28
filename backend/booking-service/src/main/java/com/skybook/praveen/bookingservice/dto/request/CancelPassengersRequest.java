package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Cancel one or more travellers off a booking (business rules). Carries the
 * BookingPassenger ids to cancel. If they cover every remaining passenger the
 * whole booking is cancelled; otherwise only those passengers are, and the
 * booking lives on for the rest.
 */
public record CancelPassengersRequest(

        @NotEmpty(message = "Select at least one passenger to cancel")
        List<Long> bookingPassengerIds

) {
}
