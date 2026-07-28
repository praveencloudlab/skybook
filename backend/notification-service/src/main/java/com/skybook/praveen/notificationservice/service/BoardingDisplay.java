package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Display rules shared by the boarding-pass PDF and the check-in email so the
 * document a passenger receives matches the pass the frontend renders,
 * character for character.
 *
 * <p>The rules mirror the frontend's BoardingPassCard/printable exactly:
 *
 * <ul>
 *   <li><b>Boarding clock</b> - boarding must read EARLIER than departure.
 *       checkin-service currently stamps boardingTime with the departure
 *       clock, so unless the event carries a genuinely earlier time the
 *       boarding display derives as departure - 40 minutes, and the
 *       gate-arrival advisory 30 minutes before that.</li>
 *   <li><b>Airport labels</b> - the seed's airports use the frontend's
 *       display names ("London Heathrow", "New York JFK"), falling back to
 *       {@link AirportCityLookup} for anything else.</li>
 * </ul>
 */
final class BoardingDisplay {

    /** Matches the frontend AIRPORTS list (frontend/src/api/flights.ts). */
    private static final Map<String, String> SEED_AIRPORTS = Map.ofEntries(
            Map.entry("LHR", "London Heathrow"),
            Map.entry("MAN", "Manchester"),
            Map.entry("EDI", "Edinburgh"),
            Map.entry("GLA", "Glasgow"),
            Map.entry("BHX", "Birmingham"),
            Map.entry("JFK", "New York JFK"),
            Map.entry("ATL", "Atlanta"),
            Map.entry("DXB", "Dubai"),
            Map.entry("DOH", "Doha"),
            Map.entry("AUH", "Abu Dhabi"),
            Map.entry("DEL", "Delhi"),
            Map.entry("BOM", "Mumbai"),
            Map.entry("HKG", "Hong Kong"),
            Map.entry("JNB", "Johannesburg"),
            Map.entry("NBO", "Nairobi"),
            Map.entry("CDG", "Paris"));

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private BoardingDisplay() {
    }

    /** The boarding time actually shown - server value only when genuinely earlier. */
    static LocalDateTime effectiveBoarding(CheckInEvent event) {
        LocalDateTime departure = event.getDepartureTime();
        LocalDateTime boarding = event.getBoardingTime();
        if (boarding != null && departure != null
                && boarding.toLocalTime().isBefore(departure.toLocalTime())) {
            return departure.toLocalDate().atTime(boarding.toLocalTime());
        }
        if (departure != null) {
            return departure.minusMinutes(40);
        }
        return boarding;
    }

    /** Gate-arrival advisory: 30 minutes before boarding starts. */
    static LocalDateTime gateBy(CheckInEvent event) {
        LocalDateTime boarding = effectiveBoarding(event);
        return boarding != null ? boarding.minusMinutes(30) : null;
    }

    static String clock(LocalDateTime value) {
        return value != null ? CLOCK.format(value) : "-";
    }

    static String date(LocalDateTime value) {
        return value != null ? DATE.format(value) : "-";
    }

    static String stamp(LocalDateTime value) {
        return value != null ? STAMP.format(value) : "-";
    }

    /** ECONOMY -> Economy, PREMIUM_ECONOMY -> Premium Economy, etc. */
    static String cabinLabel(String travelClass) {
        if (travelClass == null || travelClass.isBlank()) {
            return "-";
        }
        String[] words = travelClass.trim().split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ENGLISH));
        }
        return label.length() > 0 ? label.toString() : "-";
    }

    /** The airport display name the frontend shows for this code. */
    static String airportLabel(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String seeded = SEED_AIRPORTS.get(code);
        if (seeded != null) {
            return seeded;
        }
        String city = AirportCityLookup.cityFor(code);
        return city != null ? city : "";
    }
}
