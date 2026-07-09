package com.skybook.praveen.notificationservice.service;

import java.util.Map;

/**
 * Static IATA airline-code -> brand lookup, same rationale and pattern as
 * {@link AirportCityLookup}: flight-service stores an airlineCode on Flight,
 * but nothing downstream (booking-service, checkin-service events) carries
 * it or a display name/logo, and there is no logo asset anywhere in the
 * repo. Rather than hotlink real trademarked logo images from the internet
 * (fragile at send-time, legally murky to redistribute), each airline gets a
 * monogram badge rendered from its own brand colors - built from the airline
 * code embedded in the flight number (e.g. "BA178" -> "BA"), so no schema
 * changes were needed to thread a new field through every service.
 */
final class AirlineLookup {

    record AirlineBrand(String code, String displayName, String primaryColor, String secondaryColor) {
    }

    private static final AirlineBrand DEFAULT =
            new AirlineBrand("SB", "SkyBook Airways", "#0b3d91", "#E8B923");

    private static final Map<String, AirlineBrand> BRANDS = Map.ofEntries(
            Map.entry("AF", new AirlineBrand("AF", "Air France", "#002157", "#ED1C24")),
            Map.entry("AI", new AirlineBrand("AI", "Air India", "#D9272E", "#F5A623")),
            Map.entry("BA", new AirlineBrand("BA", "British Airways", "#075AAA", "#EB2226")),
            Map.entry("CX", new AirlineBrand("CX", "Cathay Pacific", "#00615A", "#BFA46F")),
            Map.entry("EK", new AirlineBrand("EK", "Emirates", "#D71921", "#C8A96A")),
            Map.entry("EY", new AirlineBrand("EY", "Etihad Airways", "#A98249", "#3C2E1E")),
            Map.entry("LH", new AirlineBrand("LH", "Lufthansa", "#05164D", "#FDB913")),
            Map.entry("QF", new AirlineBrand("QF", "Qantas", "#E40000", "#1D1D1B")),
            Map.entry("QR", new AirlineBrand("QR", "Qatar Airways", "#5C0632", "#A17A2D")),
            Map.entry("SQ", new AirlineBrand("SQ", "Singapore Airlines", "#0D2240", "#F5A623")),
            Map.entry("TK", new AirlineBrand("TK", "Turkish Airlines", "#C70A0C", "#58595B")),
            Map.entry("VS", new AirlineBrand("VS", "Virgin Atlantic", "#B7094C", "#E10A0A"))
    );

    private AirlineLookup() {
    }

    /** Brand for the airline encoded in a flight number (e.g. "BA178"), or a generic SkyBook brand if unknown. */
    static AirlineBrand forFlightNumber(String flightNumber) {
        String code = codeFromFlightNumber(flightNumber);
        return code == null ? DEFAULT : BRANDS.getOrDefault(code, DEFAULT);
    }

    private static String codeFromFlightNumber(String flightNumber) {
        if (flightNumber == null) {
            return null;
        }
        int i = 0;
        while (i < flightNumber.length() && Character.isLetter(flightNumber.charAt(i))) {
            i++;
        }
        return i == 0 ? null : flightNumber.substring(0, i).toUpperCase();
    }
}
