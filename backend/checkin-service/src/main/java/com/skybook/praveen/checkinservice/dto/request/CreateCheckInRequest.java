package com.skybook.praveen.checkinservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Manual/direct check-in-record creation. The normal path is the
 * BookingEvent CONFIRMED consumer; this exists for testing and back-office
 * flows, same rationale as payment-service's CreatePaymentRequest.
 */
public record CreateCheckInRequest(

        @NotNull(message = "bookingId is required")
        Long bookingId,

        @NotBlank(message = "bookingReference is required")
        @Size(max = 10, message = "bookingReference must be at most 10 characters")
        String bookingReference,

        @NotNull(message = "bookingPassengerId is required")
        Long bookingPassengerId,

        @NotNull(message = "flightId is required")
        Long flightId,

        String flightNumber,

        @Size(min = 3, max = 3, message = "originAirportCode must be exactly 3 characters")
        String originAirportCode,

        @Size(min = 3, max = 3, message = "destinationAirportCode must be exactly 3 characters")
        String destinationAirportCode,

        LocalDateTime departureTime,

        @NotBlank(message = "passengerName is required")
        @Size(max = 200, message = "passengerName must be at most 200 characters")
        String passengerName,

        String contactEmail,

        String seatNumber,

        String travelClass,

        String fareType,

        /**
         * Seat surcharge the passenger PAID at booking (SEAT_SELECTION_MODULE.md
         * §9) - the free-seat-change ceiling. Null on legacy events => 0.
         */
        BigDecimal seatSurchargeEntitlement,

        /** ISO-4217 of the entitlement; null on legacy events. */
        String entitlementCurrency,

        /**
         * The booking owner's JWT subject (SECURITY_HARDENING_MODULE.md §4.2),
         * from the CONFIRMED event. Null on legacy events => ADMIN/SERVICE-only.
         */
        String ownerSubject,

        boolean documentVerified

) {
}
