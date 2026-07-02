package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record BookingContactRequest(

        @NotBlank(message = "Contact name is required")
        String contactName,

        @NotBlank(message = "Contact email is required")
        @Email(message = "Contact email must be a valid email address")
        String contactEmail,

        String contactPhone

) {
}
