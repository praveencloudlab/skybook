package com.skybook.praveen.flightservice.dto.response;

import java.util.List;

/**
 * One bookable trip option for a route+date: a direct flight or a 1-2 stop
 * connection assembled from scheduled legs, with the layover wait at each
 * stop spelled out - the metasearch presentation.
 */
public record ItineraryResponse(

        List<FlightResponse> legs,

        int stops,

        long totalDurationMinutes,

        /** Waiting time at each stop, in leg order (empty for direct). */
        List<Long> layoverMinutes

) {
}
