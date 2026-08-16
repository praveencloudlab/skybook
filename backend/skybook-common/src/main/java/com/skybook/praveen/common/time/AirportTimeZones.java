package com.skybook.praveen.common.time;

import java.time.Duration;
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
            Map.entry("SYD", ZoneId.of("Australia/Sydney")),
            Map.entry("HYD", ZoneId.of("Asia/Kolkata")),
            Map.entry("VTZ", ZoneId.of("Asia/Kolkata")),
            Map.entry("MAA", ZoneId.of("Asia/Kolkata")),
            Map.entry("BLR", ZoneId.of("Asia/Kolkata")),
            Map.entry("CCU", ZoneId.of("Asia/Kolkata")),
            Map.entry("LAX", ZoneId.of("America/Los_Angeles")),
            Map.entry("SFO", ZoneId.of("America/Los_Angeles")),
            Map.entry("ORD", ZoneId.of("America/Chicago")),
            Map.entry("DFW", ZoneId.of("America/Chicago")),
            Map.entry("MIA", ZoneId.of("America/New_York")));

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

    /**
     * Time actually spent travelling between two airports.
     *
     * <p>Departure and arrival are wall clocks at their OWN airports, so
     * subtracting one from the other measures nothing real - it mixes the
     * flight with the offset between the two zones. London 21:25 to Singapore
     * 17:30 next day subtracts to 20h 05m; the aircraft is in the air for
     * 13h 05m. Eastbound the error inflates, westbound it can go negative and
     * make an eight-hour flight look like it lands before it left.
     *
     * <p>Each end is resolved to a real instant on its own zone, which also
     * means a flight crossing a daylight-saving change is measured correctly:
     * the clocks move, the elapsed time does not.
     *
     * <p>Lives here, next to the zone table, because three services were each
     * about to compute it their own way.
     */
    public static Duration elapsedBetween(String originAirportCode, LocalDateTime departureLocal,
                                          String destinationAirportCode, LocalDateTime arrivalLocal) {
        if (departureLocal == null || arrivalLocal == null) {
            return Duration.ZERO;
        }
        return Duration.between(
                departureLocal.atZone(zoneOf(originAirportCode)),
                arrivalLocal.atZone(zoneOf(destinationAirportCode)));
    }
}
