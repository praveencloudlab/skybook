package com.skybook.praveen.bookingservice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-passenger, per-departure taxes: the LHR pair by cabin, the DXB and
 * India charges, the generic fallback, and the merge/serialize round used
 * by the booking and its ticket.
 */
class TaxPolicyTest {

    private final TaxPolicy policy = new TaxPolicy(true);

    @Test
    void lhrChargesApdByCabinPlusServiceCharge() {
        // 2025/26 rates: any departure before 1 Apr 2026.
        java.time.LocalDate fy2025 = java.time.LocalDate.of(2026, 3, 1);
        List<TaxPolicy.TaxLine> economy = policy.linesFor("LHR", "ECONOMY", fy2025);
        assertThat(economy).extracting(TaxPolicy.TaxLine::code).containsExactly("GB", "UB");
        assertThat(economy.get(0).amount()).isEqualByComparingTo("90.00");

        List<TaxPolicy.TaxLine> first = policy.linesFor("LHR", "FIRST", fy2025);
        assertThat(first.get(0).amount()).isEqualByComparingTo("216.00");
        assertThat(first.get(1).amount()).isEqualByComparingTo("29.10");
    }

    @Test
    void apdRatesFollowTheDepartureDateAcrossTheFiscalBoundary() {
        // Band B rose on 1 Apr 2026: £90->£102 economy, £216->£244 premium.
        java.time.LocalDate lastOldDay = java.time.LocalDate.of(2026, 3, 31);
        java.time.LocalDate firstNewDay = java.time.LocalDate.of(2026, 4, 1);

        assertThat(policy.linesFor("LHR", "FIRST", lastOldDay).get(0).amount()).isEqualByComparingTo("216.00");
        assertThat(policy.linesFor("LHR", "FIRST", firstNewDay).get(0).amount()).isEqualByComparingTo("244.00");
        assertThat(policy.linesFor("LHR", "ECONOMY", lastOldDay).get(0).amount()).isEqualByComparingTo("90.00");
        assertThat(policy.linesFor("LHR", "ECONOMY", firstNewDay).get(0).amount()).isEqualByComparingTo("102.00");
        // The service charge is not APD and does not move with it.
        assertThat(policy.linesFor("LHR", "FIRST", firstNewDay).get(1).amount()).isEqualByComparingTo("29.10");
    }

    @Test
    void dubaiIndiaAndTheFallbackEachHaveTheirCharge() {
        assertThat(policy.linesFor("DXB", "FIRST").get(0).code()).isEqualTo("AE");
        assertThat(policy.linesFor("HYD", "ECONOMY").get(0).code()).isEqualTo("IN");
        assertThat(policy.linesFor("VTZ", "ECONOMY").get(0).code()).isEqualTo("IN");
        assertThat(policy.linesFor("JFK", "ECONOMY").get(0).code()).isEqualTo("XT");
    }

    @Test
    void aThroughTicketViaDubaiPaysBothDepartures() {
        // LHR->DXB->HYD, FIRST, Aug 2026 departure (2026/27 APD):
        // GB 244 + UB 29.10 + AE 16.30.
        java.time.LocalDate aug2026 = java.time.LocalDate.of(2026, 8, 22);
        List<TaxPolicy.TaxLine> lines = new ArrayList<>();
        lines.addAll(policy.linesFor("LHR", "FIRST", aug2026));
        lines.addAll(policy.linesFor("DXB", "FIRST", aug2026));
        Map<String, BigDecimal> merged = TaxPolicy.merge(lines);

        assertThat(merged.keySet()).containsExactly("GB", "UB", "AE");
        assertThat(merged.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("289.40");
        assertThat(TaxPolicy.serialize(merged)).isEqualTo("GB:244.00;UB:29.10;AE:16.30");
    }

    @Test
    void disabledPolicyAssessesNothing() {
        assertThat(new TaxPolicy(false).linesFor("LHR", "FIRST")).isEmpty();
        assertThat(TaxPolicy.serialize(Map.of())).isNull();
    }
}
