package com.skybook.praveen.flightservice.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terminals are printed on the e-ticket, so every carrier/airport pair the
 * policy special-cases is pinned here - a silent switch-branch edit would
 * otherwise send a passenger to the wrong building. Both the hub branches and
 * the "unknown airport" fallback are exercised.
 */
class TerminalPolicyTest {

    @ParameterizedTest
    @CsvSource({
            // Heathrow: BA lives in T5, EK/VS in T3, the Gulf/SkyTeam carriers in T4.
            "BA, LHR, 5",
            "EK, LHR, 3",
            "VS, LHR, 3",
            "EY, LHR, 4",
            "QR, LHR, 4",
            "AF, LHR, 4",
            "KL, LHR, 4",
            "SB, LHR, 2",
            "LH, LHR, 2",
            "TK, LHR, 2",
            // Dubai: Emirates has its own T3, everyone else T1.
            "EK, DXB, 3",
            "BA, DXB, 1",
            // Paris CDG: Air France in 2E, the rest in T1.
            "AF, CDG, 2E",
            "BA, CDG, 1",
            // Frankfurt: Lufthansa in T1, everyone else T2.
            "LH, FRA, 1",
            "BA, FRA, 2",
            // JFK: BA in T8, EK/DL in T4, the rest T1.
            "BA, JFK, 8",
            "EK, JFK, 4",
            "DL, JFK, 4",
            "SB, JFK, 1",
            // Singapore: SQ in T3, the rest T1.
            "SQ, SIN, 3",
            "BA, SIN, 1",
            // Lettered and single-terminal airports ignore the carrier.
            "EY, AUH, A",
            "SB, AUH, A",
            "SB, JNB, A",
            "DL, ATL, I",
            "AI, DEL, 3",
            "AI, BOM, 2",
            "SB, MAN, 2",
            // Single-terminal airports fall through to the default.
            "QR, DOH, 1",
            "TK, IST, 1",
            "SB, SYD, 1",
            "SB, HKG, 1",
            "SB, EDI, 1",
            "SB, GLA, 1",
            "SB, BHX, 1",
            "SB, NBO, 1"
    })
    void assignsTheCarriersRealTerminal(String airline, String airport, String expected) {
        assertThat(TerminalPolicy.terminalFor(airline, airport)).isEqualTo(expected);
    }

    @Test
    void lowercaseInputIsNormalisedBeforeMatching() {
        assertThat(TerminalPolicy.terminalFor("ba", "lhr")).isEqualTo("5");
        assertThat(TerminalPolicy.terminalFor("ek", "dxb")).isEqualTo("3");
        assertThat(TerminalPolicy.terminalFor("af", "cdg")).isEqualTo("2E");
    }

    @Test
    void nullsAreTreatedAsUnknownRatherThanExploding() {
        assertThat(TerminalPolicy.terminalFor(null, null)).isEqualTo("1");
        assertThat(TerminalPolicy.terminalFor(null, "LHR")).isEqualTo("2");
        assertThat(TerminalPolicy.terminalFor("BA", null)).isEqualTo("1");
    }

    @Test
    void unknownAirportsGetTerminalOneRatherThanNoTerminal() {
        assertThat(TerminalPolicy.terminalFor("SB", "XXX")).isEqualTo("1");
    }
}
