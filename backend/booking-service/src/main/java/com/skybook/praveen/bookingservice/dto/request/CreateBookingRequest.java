package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateBookingRequest(

        @NotNull(message = "customerId is required")
        Long customerId,

        @NotNull(message = "flightId is required")
        Long flightId,

        @NotEmpty(message = "At least one passenger is required")
        @Size(max = 9, message = "A single booking supports at most 9 passengers")
        @Valid
        List<PassengerBookingDetail> passengers,

        @NotNull(message = "Contact details are required")
        @Valid
        BookingContactRequest contact,

        String remarks

) {
}
