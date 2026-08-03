package com.skybook.praveen.bookingservice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of the fare calendar: how many bookable departures the route has and
 * the cheapest per-guest fare for the requested cabin on that date - computed
 * by the same FareCalculator every quote and booking uses, so the calendar can
 * never disagree with checkout.
 */
public record FareCalendarDayResponse(

        LocalDate date,

        int flights,

        BigDecimal minFare,

        String currency

) {
}
