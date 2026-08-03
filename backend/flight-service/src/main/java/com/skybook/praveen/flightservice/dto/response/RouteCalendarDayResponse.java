package com.skybook.praveen.flightservice.dto.response;

import java.time.LocalDate;

/**
 * One day of a route's availability calendar: how many bookable (non-cancelled)
 * departures the route has on that date. Days with zero flights are simply
 * absent from the response - the calendar UI renders them as unavailable.
 */
public record RouteCalendarDayResponse(

        LocalDate date,

        int flights

) {
}
