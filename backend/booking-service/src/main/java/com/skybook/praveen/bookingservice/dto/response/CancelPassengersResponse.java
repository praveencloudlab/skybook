package com.skybook.praveen.bookingservice.dto.response;

import java.math.BigDecimal;

/**
 * Outcome of a passenger cancellation: the booking as it now stands, the refund
 * calculated for the cancelled passengers, and whether that emptied the booking
 * (all passengers cancelled → the booking itself is CANCELLED).
 */
public record CancelPassengersResponse(

        BookingResponse booking,

        BigDecimal refundAmount,

        boolean bookingCancelled

) {
}
