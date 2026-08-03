package com.skybook.praveen.flightservice.domain;

/**
 * Which terminal a carrier uses at each airport - the real-world assignments
 * for the network's airports (e.g. Emirates operates LHR T3 and its own DXB
 * T3; BA lives in LHR T5; Hamad and Istanbul are single-terminal). One
 * source of truth for Java-created flights; the SQL seeds embed the same
 * CASE so seeded and admin-created schedules agree.
 *
 * <p>Airports with one passenger terminal return that terminal. Unknown
 * airports default to "1" - a real terminal name is still better than none.
 */
public final class TerminalPolicy {

    private TerminalPolicy() {
    }

    public static String terminalFor(String airlineCode, String airportCode) {
        String airline = airlineCode == null ? "" : airlineCode.toUpperCase();
        String airport = airportCode == null ? "" : airportCode.toUpperCase();
        return switch (airport) {
            case "LHR" -> switch (airline) {
                case "BA" -> "5";
                case "EK", "VS" -> "3";
                case "EY", "QR", "AF", "KL" -> "4";
                default -> "2"; // SB, LH, TK, AI, SQ and friends
            };
            case "DXB" -> "EK".equals(airline) ? "3" : "1";
            case "CDG" -> "AF".equals(airline) ? "2E" : "1";
            case "FRA" -> "LH".equals(airline) ? "1" : "2";
            case "JFK" -> switch (airline) {
                case "BA" -> "8";
                case "EK", "DL" -> "4";
                default -> "1";
            };
            case "SIN" -> "SQ".equals(airline) ? "3" : "1";
            case "AUH", "JNB" -> "A";
            case "ATL" -> "I";
            case "DEL" -> "3";
            case "BOM", "MAN" -> "2";
            default -> "1"; // DOH, IST, SYD, HKG, EDI, GLA, BHX, NBO - single-terminal
        };
    }
}
