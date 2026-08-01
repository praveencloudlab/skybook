package com.skybook.praveen.common.time;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Flight times across the fleet are stored AIRPORT-LOCAL with no zone (the
 * schedule reads like a timetable). Every "how long until departure" rule -
 * booking cutoff, check-in windows, cancellation tiers, no-show/flown sweeps -
 * must therefore compare the departure against the clock AT THAT AIRPORT,
 * not the server's UTC clock: for a JFK departure the difference is four to
 * five hours, enough to close check-in at breakfast or refund the wrong tier.
 *
 * <p>One entry per airport the seeded schedule serves. An unknown code falls
 * back to UTC - wrong by at most the old behaviour, never worse.
 */
public final class AirportTimeZones {

    private static final Map<String, ZoneId> ZONES = Map.ofEntries(
            Map.entry("ATL", ZoneId.of("America/New_York")),
            Map.entry("JFK", ZoneId.of("America/New_York")),
            Map.entry("LHR", ZoneId.of("Europe/London")),
            Map.entry("MAN", ZoneId.of("Europe/London")),
            Map.entry("BHX", ZoneId.of("Europe/London")),
            Map.entry("EDI", ZoneId.of("Europe/London")),
            Map.entry("GLA", ZoneId.of("Europe/London")),
            Map.entry("CDG", ZoneId.of("Europe/Paris")),
            Map.entry("FRA", ZoneId.of("Europe/Berlin")),
            Map.entry("IST", ZoneId.of("Europe/Istanbul")),
            Map.entry("JNB", ZoneId.of("Africa/Johannesburg")),
            Map.entry("NBO", ZoneId.of("Africa/Nairobi")),
            Map.entry("DXB", ZoneId.of("Asia/Dubai")),
            Map.entry("AUH", ZoneId.of("Asia/Dubai")),
            Map.entry("DOH", ZoneId.of("Asia/Qatar")),
            Map.entry("BOM", ZoneId.of("Asia/Kolkata")),
            Map.entry("DEL", ZoneId.of("Asia/Kolkata")),
            Map.entry("HKG", ZoneId.of("Asia/Hong_Kong")),
            Map.entry("SIN", ZoneId.of("Asia/Singapore")),
            Map.entry("SYD", ZoneId.of("Australia/Sydney")));

    private AirportTimeZones() {
    }

    public static ZoneId zoneOf(String airportCode) {
        return airportCode == null ? ZoneOffset.UTC
                : ZONES.getOrDefault(airportCode.toUpperCase(), ZoneOffset.UTC);
    }

    /** The wall clock at that airport right now - the ONLY correct "now" to compare its departures against. */
    public static LocalDateTime nowAt(String airportCode) {
        return LocalDateTime.now(zoneOf(airportCode));
    }
}
