package com.skybook.praveen.notificationservice.service;

import java.util.Map;

/**
 * Static IATA airport code -> city name lookup for the email template's
 * route card. flight-service treats origin/destination as free-text 3-char
 * codes (no airport reference table anywhere in the system), so this is a
 * best-effort map of common airports; unknown codes just render without a
 * city line.
 */
final class AirportCityLookup {

    private static final Map<String, String> CITY_BY_CODE = Map.ofEntries(
            Map.entry("LHR", "London"),
            Map.entry("LGW", "London"),
            Map.entry("LCY", "London"),
            Map.entry("STN", "London"),
            Map.entry("JFK", "New York"),
            Map.entry("EWR", "New York"),
            Map.entry("LGA", "New York"),
            Map.entry("LAX", "Los Angeles"),
            Map.entry("SFO", "San Francisco"),
            Map.entry("ORD", "Chicago"),
            Map.entry("MIA", "Miami"),
            Map.entry("SEA", "Seattle"),
            Map.entry("BOS", "Boston"),
            Map.entry("IAD", "Washington, D.C."),
            Map.entry("DFW", "Dallas"),
            Map.entry("ATL", "Atlanta"),
            Map.entry("YYZ", "Toronto"),
            Map.entry("YVR", "Vancouver"),
            Map.entry("CDG", "Paris"),
            Map.entry("ORY", "Paris"),
            Map.entry("FRA", "Frankfurt"),
            Map.entry("MUC", "Munich"),
            Map.entry("AMS", "Amsterdam"),
            Map.entry("MAD", "Madrid"),
            Map.entry("BCN", "Barcelona"),
            Map.entry("FCO", "Rome"),
            Map.entry("MXP", "Milan"),
            Map.entry("ZRH", "Zurich"),
            Map.entry("VIE", "Vienna"),
            Map.entry("CPH", "Copenhagen"),
            Map.entry("OSL", "Oslo"),
            Map.entry("ARN", "Stockholm"),
            Map.entry("DUB", "Dublin"),
            Map.entry("LIS", "Lisbon"),
            Map.entry("IST", "Istanbul"),
            Map.entry("SVO", "Moscow"),
            Map.entry("DXB", "Dubai"),
            Map.entry("AUH", "Abu Dhabi"),
            Map.entry("DOH", "Doha"),
            Map.entry("RUH", "Riyadh"),
            Map.entry("JED", "Jeddah"),
            Map.entry("DEL", "New Delhi"),
            Map.entry("BOM", "Mumbai"),
            Map.entry("BLR", "Bengaluru"),
            Map.entry("MAA", "Chennai"),
            Map.entry("HYD", "Hyderabad"),
            Map.entry("CCU", "Kolkata"),
            Map.entry("PEK", "Beijing"),
            Map.entry("PVG", "Shanghai"),
            Map.entry("HKG", "Hong Kong"),
            Map.entry("NRT", "Tokyo"),
            Map.entry("HND", "Tokyo"),
            Map.entry("ICN", "Seoul"),
            Map.entry("SIN", "Singapore"),
            Map.entry("KUL", "Kuala Lumpur"),
            Map.entry("BKK", "Bangkok"),
            Map.entry("CGK", "Jakarta"),
            Map.entry("MNL", "Manila"),
            Map.entry("SYD", "Sydney"),
            Map.entry("MEL", "Melbourne"),
            Map.entry("AKL", "Auckland"),
            Map.entry("JNB", "Johannesburg"),
            Map.entry("CPT", "Cape Town"),
            Map.entry("CAI", "Cairo"),
            Map.entry("LOS", "Lagos"),
            Map.entry("NBO", "Nairobi"),
            Map.entry("GRU", "São Paulo"),
            Map.entry("GIG", "Rio de Janeiro"),
            Map.entry("EZE", "Buenos Aires"),
            Map.entry("MEX", "Mexico City"),
            Map.entry("YUL", "Montreal")
    );

    private AirportCityLookup() {
    }

    /** City name for an IATA code, or null if the code isn't in the lookup. */
    static String cityFor(String iataCode) {
        return iataCode == null ? null : CITY_BY_CODE.get(iataCode.toUpperCase());
    }
}
