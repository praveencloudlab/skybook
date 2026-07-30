package com.skybook.praveen.bookingservice.dto.response;

/**
 * One flight leg of the booking's journey (ROUND_TRIP_MODULE.md §3/§4).
 * status is DERIVED, never stored - same philosophy as booking status - so
 * the UI shows "Outbound - Completed / Return - Upcoming" without date math:
 * CANCELLED (all rows cancelled), CHECKED_IN (any active row checked in),
 * else UPCOMING. FLOWN needs the departure time, which booking-service does
 * not store; the frontend derives it from the flight it already fetches.
 */
public record BookingSegmentResponse(

        Long id,

        int segmentIndex,

        Long flightId,

        String status

) {
}
