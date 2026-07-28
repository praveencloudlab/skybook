package com.skybook.praveen.bookingservice.client;

import java.time.LocalDate;

/** flight-service's route-calendar row: a date and its bookable-departure count. */
public record RouteCalendarDay(

        LocalDate date,

        int flights

) {
}
