package com.skybook.praveen.bookingservice.dto.request;

import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * One traveler within a CreateBookingRequest. seatNumber is optional in the
 * request shape but effectively required today - ManualSeatAssignmentStrategy
 * rejects a blank seat number, since automatic assignment isn't implemented
 * yet (docs section 6/9).
 */
public record PassengerBookingDetail(

        String title,

        @NotBlank(message = "First name is required")
        String firstName,

        String middleName,

        @NotBlank(message = "Last name is required")
        String lastName,

        @NotNull(message = "Date of birth is required")
        @Past(message = "Date of birth must be in the past")
        LocalDate dob,

        String gender,

        @NotBlank(message = "Nationality is required")
        @Size(min = 3, max = 3, message = "Nationality must be a 3-letter ISO country code")
        String nationality,

        @NotBlank(message = "Passport number is required")
        @Size(max = 20, message = "Passport number must not exceed 20 characters")
        String passportNumber,

        @NotNull(message = "Passport expiry is required")
        @Future(message = "Passport expiry must be in the future")
        LocalDate passportExpiry,

        String email,

        String phone,

        @NotNull(message = "Travel class is required")
        TravelClass travelClass,

        @NotNull(message = "Fare type is required")
        FareType fareType,

        String seatNumber

) {
}
