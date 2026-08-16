package com.skybook.praveen.bookingservice.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Government and airport taxes, assessed PER PASSENGER PER DEPARTURE (the way
 * real itineraries price them) and charged at booking on top of the all-in
 * fare:
 *
 * <pre>
 *   LHR departure : GB  UK Air Passenger Duty   £90.00 economy / £216.00 other cabins
 *                   UB  UK Passenger Service Charge          £29.10
 *   DXB departure : AE  UAE Passenger Facility Charge        £16.30
 *   India (HYD,
 *   BOM, DEL...)  : IN  India UDF & K3                        £13.60
 *   anywhere else : XT  International airport charges         £11.20
 * </pre>
 *
 * Codes follow ticketing convention (GB/UB are the real IATA tax codes for
 * the UK pair). Amounts are demo-realistic 2026 figures, not a live tax
 * feed. Pure logic, no I/O; the booking stores the merged breakdown so the
 * ticket prints exactly what was charged.
 */
@Component
public class TaxPolicy {

    public record TaxLine(String code, String label, BigDecimal amount) {
    }

    private static final List<String> INDIA = List.of("HYD", "BOM", "DEL", "BLR", "MAA", "CCU", "VTZ");

    public static final Map<String, String> LABELS = Map.of(
            "GB", "UK AIR PASSENGER DUTY",
            "UB", "UK PASSENGER SERVICE CHARGE",
            "AE", "UAE PASSENGER FACILITY CHARGE",
            "IN", "INDIA UDF & K3",
            "XT", "INTL AIRPORT CHARGES");

    /** Master switch - unit fixtures construct the policy disabled. */
    private final boolean enabled;

    public TaxPolicy(@Value("${booking.taxes.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Taxes for ONE passenger departing ONE airport in a cabin. The journey's
     * total is the sum over every (leg x passenger) pair - a through-ticket
     * via DXB genuinely pays the UAE facility charge, a direct one doesn't.
     */
    public List<TaxLine> linesFor(String departureAirport, String travelClass) {
        if (!enabled || departureAirport == null) {
            return List.of();
        }
        String airport = departureAirport.toUpperCase();
        boolean premiumCabin = travelClass != null && !"ECONOMY".equals(travelClass);
        if ("LHR".equals(airport)) {
            return List.of(
                    new TaxLine("GB", LABELS.get("GB"), premiumCabin ? new BigDecimal("216.00") : new BigDecimal("90.00")),
                    new TaxLine("UB", LABELS.get("UB"), new BigDecimal("29.10")));
        }
        if ("DXB".equals(airport)) {
            return List.of(new TaxLine("AE", LABELS.get("AE"), new BigDecimal("16.30")));
        }
        if (INDIA.contains(airport)) {
            return List.of(new TaxLine("IN", LABELS.get("IN"), new BigDecimal("13.60")));
        }
        return List.of(new TaxLine("XT", LABELS.get("XT"), new BigDecimal("11.20")));
    }

    /** Merge lines by code, preserving first-seen order - the ticket's tax rows. */
    public static Map<String, BigDecimal> merge(List<TaxLine> lines) {
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        for (TaxLine line : lines) {
            merged.merge(line.code(), line.amount(), BigDecimal::add);
        }
        return merged;
    }

    /** Compact storage form: "GB:216.00;UB:29.10;AE:16.30". */
    public static String serialize(Map<String, BigDecimal> merged) {
        if (merged.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        merged.forEach((code, amount) -> {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(code).append(':').append(amount.toPlainString());
        });
        return sb.toString();
    }
}
