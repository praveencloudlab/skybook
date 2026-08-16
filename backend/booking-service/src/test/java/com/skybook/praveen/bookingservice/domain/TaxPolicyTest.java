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
        List<TaxPolicy.TaxLine> economy = policy.linesFor("LHR", "ECONOMY");
        assertThat(economy).extracting(TaxPolicy.TaxLine::code).containsExactly("GB", "UB");
        assertThat(economy.get(0).amount()).isEqualByComparingTo("90.00");

        List<TaxPolicy.TaxLine> first = policy.linesFor("LHR", "FIRST");
        assertThat(first.get(0).amount()).isEqualByComparingTo("216.00");
        assertThat(first.get(1).amount()).isEqualByComparingTo("29.10");
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
        // LHR->DXB->HYD, FIRST, one passenger: GB 216 + UB 29.10 + AE 16.30.
        List<TaxPolicy.TaxLine> lines = new ArrayList<>();
        lines.addAll(policy.linesFor("LHR", "FIRST"));
        lines.addAll(policy.linesFor("DXB", "FIRST"));
        Map<String, BigDecimal> merged = TaxPolicy.merge(lines);

        assertThat(merged.keySet()).containsExactly("GB", "UB", "AE");
        assertThat(merged.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("261.40");
        assertThat(TaxPolicy.serialize(merged)).isEqualTo("GB:216.00;UB:29.10;AE:16.30");
    }

    @Test
    void disabledPolicyAssessesNothing() {
        assertThat(new TaxPolicy(false).linesFor("LHR", "FIRST")).isEmpty();
        assertThat(TaxPolicy.serialize(Map.of())).isNull();
    }
}
