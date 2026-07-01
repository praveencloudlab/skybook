package com.skybook.praveen.bookingservice.dto.request;

import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;

import java.time.LocalDate;

/**
 * All fields optional filters - a null field means "don't filter on this",
 * same convention as flight-service's FlightScheduleSearchRequest.
 * travelDate filters by the associated flight's departure date range is out
 * of scope here (booking-service doesn't own flight schedules) - travelDate
 * instead filters bookingDate for now; revisit once itinerary/flight lookups
 * are wired through the facade.
 */
public record BookingSearchRequest(

        String bookingReference,

        Long flightId,

        String passengerName,

        String passportNumber,

        BookingStatus bookingStatus,

        PaymentStatus paymentStatus,

        LocalDate travelDate,

        LocalDate bookingDate,

        String email,

        String phone

) {
}
