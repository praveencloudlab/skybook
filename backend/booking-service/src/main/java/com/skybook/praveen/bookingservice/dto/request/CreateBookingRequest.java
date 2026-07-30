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

        /**
         * Present = single-PNR round trip (ROUND_TRIP_MODULE.md §4): the
         * return leg's flight, booked as segment 1 of the same booking. Every
         * passenger gets one row per segment; seat picks apply to the
         * outbound (v1 - return seats auto-assign) and extraBags to each
         * segment. One payment covers the combined total.
         */
        Long returnFlightId,

        /**
         * Present = same-carrier THROUGH-TICKET (ROUND_TRIP_MODULE.md,
         * through-ticketing extension): the onward legs of a one-way
         * connection, in travel order after flightId. All legs become
         * segments of direction 0 in ONE booking - one payment, bags charged
         * once, coupons per leg. Cannot be combined with returnFlightId in
         * v1; mixed-carrier connections stay self-transfer (separate
         * bookings per leg, built by the client).
         */
        @Size(max = 2, message = "At most 2 connection legs (a 2-stop itinerary)")
        List<Long> connectionFlightIds,

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
