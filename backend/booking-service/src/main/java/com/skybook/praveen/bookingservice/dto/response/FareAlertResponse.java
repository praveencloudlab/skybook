package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.TravelClass;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One watched fare, with TODAY's deterministic fare alongside what was last notified. */
public record FareAlertResponse(

        Long id,

        String originAirportCode,

        String destinationAirportCode,

        LocalDate travelDate,

        TravelClass travelClass,

        /** The cheapest fare RIGHT NOW from the same calculator checkout uses. */
        BigDecimal currentFare,

        BigDecimal lastNotifiedFare,

        String currency

) {
}
