package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Fare options for one flight (SEAT_SELECTION_MODULE.md §11): the ONLY place
 * inventory cabin availability and booking's base fares meet - "Economy from
 * X, Business from Y". Base fares only: a chosen seat adds its listed
 * surcharge (per seat on the seat map) on top; auto-assignment adds nothing.
 *
 * cabins lists exactly what the flight's aircraft sells - a missing cabin IS
 * the §7 "this flight has no First-class cabin" answer, pre-booking.
 * availableSeats is null when the flight has no seat inventory record
 * (fares still quotable, availability unknown - hold-if-exists policy).
 */
public record QuoteResponse(

        Long flightId,

        String currency,

        List<CabinQuote> cabins

) {

    public record CabinQuote(

            TravelClass travelClass,

            Integer availableSeats,

            /** Cabin base fare per fare type (FareCalculator - SAVER/FLEXI/PREMIUM). */
            Map<FareType, BigDecimal> baseFares,

            /** The cheapest base fare - the "from" price. */
            BigDecimal fromFare

    ) {
    }
}
