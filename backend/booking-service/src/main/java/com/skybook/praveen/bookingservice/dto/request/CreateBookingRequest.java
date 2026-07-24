package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateBookingRequest(

        /**
         * OPTIONAL legacy field (FRONTEND_MODULE.md §10.3).
         *
         * <p>Ownership does <b>not</b> come from here - it comes from
         * {@code ownerSubject}, captured from the authenticated principal, and that
         * is what every OWNER check compares against. This field is written and
         * echoed back but nothing authorizes or looks up by it
         * ({@code findByCustomerId} is exposed on no endpoint).
         *
         * <p>It was {@code @NotNull}, which forced every client to invent a
         * meaningless number. It is now optional. Note it could not simply be
         * <i>derived</i>: the JWT carries {@code sub} (the email), roles and
         * token_type - there is no numeric user id to derive from, and adding one
         * would change the frozen security module's token shape.
         */
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
