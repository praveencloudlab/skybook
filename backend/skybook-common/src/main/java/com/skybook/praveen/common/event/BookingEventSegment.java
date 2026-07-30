package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * One flight leg of a multi-segment booking (ROUND_TRIP_MODULE.md §6):
 * segment 0 = outbound, 1 = return. Passengers nest UNDER their segment so
 * flight context is stated once per leg instead of repeated on every
 * passenger entry. Events without segments (pre-round-trip producers) fall
 * back to the event's top-level flight fields + flat passenger list.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingEventSegment {

    private Integer segmentIndex;

    private Long flightId;

    private String flightNumber;

    private String originAirportCode;

    private String destinationAirportCode;

    /** Pre-formatted, e.g. "2026-07-08 21:25" - same convention as the event's top-level times. */
    private String departureTime;

    private String arrivalTime;

    /** This leg's passenger rows (seat, fare, check-in state are per-leg facts). */
    private List<BookingEventPassenger> passengers;
}
