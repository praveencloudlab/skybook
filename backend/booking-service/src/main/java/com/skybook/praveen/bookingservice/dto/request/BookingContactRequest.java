package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BookingContactRequest(

        @NotBlank(message = "Contact name is required")
        String contactName,

        @NotBlank(message = "Contact email is required")
        @Email(message = "Contact email must be a valid email address")
        String contactEmail,

        // Mandatory, the way real carriers make it mandatory: disruption
        // messaging - gate changes, delays, cancellations - reaches the
        // passenger by phone, and a booking that cannot be reached is an
        // operational liability. Loose international format: optional +,
        // then 7-15 digits with common separators tolerated.
        @NotBlank(message = "Contact phone is required")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()\\-]{5,18}[0-9]$",
                 message = "Contact phone must be a valid phone number")
        String contactPhone

) {
}
