package com.skybook.praveen.common.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lookup that every "how long until departure" rule on the platform leans
 * on - booking cutoff, check-in windows, cancellation tiers, no-show/flown
 * sweeps. A wrong zone here does not throw, it silently refunds the wrong tier
 * or closes check-in hours early, so the offsets are asserted as real numbers
 * on real instants (including across a DST boundary, which is the entire
 * reason zones are stored instead of fixed offsets).
 */
class AirportTimeZonesTest {

    /** Northern-hemisphere winter - Europe/North America on standard time. */
    private static final LocalDateTime JANUARY = LocalDateTime.of(2026, 1, 15, 12, 0);

    /** Northern-hemisphere summer - Europe/North America on daylight time. */
    private static final LocalDateTime JULY = LocalDateTime.of(2026, 7, 15, 12, 0);

    private static ZoneOffset offsetAt(String airportCode, LocalDateTime when) {
        return ZonedDateTime.of(when, AirportTimeZones.zoneOf(airportCode)).getOffset();
    }

    @Nested
    @DisplayName("zoneOf: every seeded airport resolves to its own region zone")
    class KnownCodes {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "ATL, America/New_York",
                "JFK, America/New_York",
                "LHR, Europe/London",
                "MAN, Europe/London",
                "BHX, Europe/London",
                "EDI, Europe/London",
                "GLA, Europe/London",
                "CDG, Europe/Paris",
                "FRA, Europe/Berlin",
                "IST, Europe/Istanbul",
                "JNB, Africa/Johannesburg",
                "NBO, Africa/Nairobi",
                "DXB, Asia/Dubai",
                "AUH, Asia/Dubai",
                "DOH, Asia/Qatar",
                "BOM, Asia/Kolkata",
                "DEL, Asia/Kolkata",
                "HKG, Asia/Hong_Kong",
                "SIN, Asia/Singapore",
                "SYD, Australia/Sydney"
        })
        @DisplayName("each seeded airport code maps to the documented ZoneId")
        void seededAirportCodeMapsToItsZone(String airportCode, String expectedZone) {
            assertThat(AirportTimeZones.zoneOf(airportCode)).isEqualTo(ZoneId.of(expectedZone));
        }

        @Test
        @DisplayName("the UK airports all share one zone, the UAE pair shares another")
        void airportsInTheSameCountryShareAZone() {
            assertThat(AirportTimeZones.zoneOf("MAN"))
                    .isEqualTo(AirportTimeZones.zoneOf("LHR"))
                    .isEqualTo(AirportTimeZones.zoneOf("BHX"))
                    .isEqualTo(AirportTimeZones.zoneOf("EDI"))
                    .isEqualTo(AirportTimeZones.zoneOf("GLA"));
            assertThat(AirportTimeZones.zoneOf("AUH")).isEqualTo(AirportTimeZones.zoneOf("DXB"));
            assertThat(AirportTimeZones.zoneOf("BOM")).isEqualTo(AirportTimeZones.zoneOf("DEL"));
            assertThat(AirportTimeZones.zoneOf("ATL")).isEqualTo(AirportTimeZones.zoneOf("JFK"));
        }

        @Test
        @DisplayName("neighbouring European zones are distinct ZoneIds even when the offset agrees")
        void parisAndBerlinAreDistinctZonesDespiteTheSameOffset() {
            assertThat(AirportTimeZones.zoneOf("CDG")).isNotEqualTo(AirportTimeZones.zoneOf("FRA"));
            assertThat(offsetAt("CDG", JULY)).isEqualTo(offsetAt("FRA", JULY));
        }
    }

    @Nested
    @DisplayName("zoneOf: the offsets are genuinely different, not a UTC stand-in")
    class RealOffsets {

        @ParameterizedTest(name = "{0} is UTC{1} in January")
        @CsvSource({
                "JFK, -05:00",
                "ATL, -05:00",
                "LHR, Z",
                "MAN, Z",
                "CDG, +01:00",
                "FRA, +01:00",
                "IST, +03:00",
                "JNB, +02:00",
                "NBO, +03:00",
                "DOH, +03:00",
                "DXB, +04:00",
                "AUH, +04:00",
                "DEL, +05:30",
                "BOM, +05:30",
                "SIN, +08:00",
                "HKG, +08:00",
                "SYD, +11:00"
        })
        @DisplayName("a January noon resolves to the airport's real winter offset")
        void winterOffsetsSpanTheNetwork(String airportCode, String expectedOffset) {
            assertThat(offsetAt(airportCode, JANUARY)).isEqualTo(ZoneOffset.of(expectedOffset));
        }

        @Test
        @DisplayName("India's half-hour offset survives - proof the value is a real zone, not an hour count")
        void indiaKeepsItsHalfHourOffset() {
            assertThat(offsetAt("DEL", JANUARY)).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
            assertThat(offsetAt("DEL", JULY)).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
        }

        @Test
        @DisplayName("a JFK departure is four to five hours off the server's UTC clock")
        void jfkIsHoursAwayFromUtc() {
            assertThat(offsetAt("JFK", JANUARY).getTotalSeconds())
                    .isEqualTo((int) Duration.ofHours(-5).getSeconds());
            assertThat(offsetAt("JFK", JULY).getTotalSeconds())
                    .isEqualTo((int) Duration.ofHours(-4).getSeconds());
        }
    }

    @Nested
    @DisplayName("zoneOf: daylight saving is the whole point of storing zones")
    class DaylightSaving {

        @Test
        @DisplayName("London is UTC+0 in January and UTC+1 in July")
        void londonShiftsAcrossTheDstBoundary() {
            assertThat(offsetAt("LHR", JANUARY)).isEqualTo(ZoneOffset.UTC);
            assertThat(offsetAt("LHR", JULY)).isEqualTo(ZoneOffset.ofHours(1));
        }

        @Test
        @DisplayName("New York shifts UTC-5 to UTC-4 across the DST boundary")
        void newYorkShiftsAcrossTheDstBoundary() {
            assertThat(offsetAt("JFK", JANUARY)).isEqualTo(ZoneOffset.ofHours(-5));
            assertThat(offsetAt("JFK", JULY)).isEqualTo(ZoneOffset.ofHours(-4));
        }

        @Test
        @DisplayName("Sydney shifts the other way - southern-hemisphere summer is January")
        void sydneyShiftsInTheOppositeDirection() {
            assertThat(offsetAt("SYD", JANUARY)).isEqualTo(ZoneOffset.ofHours(11));
            assertThat(offsetAt("SYD", JULY)).isEqualTo(ZoneOffset.ofHours(10));
        }

        @ParameterizedTest(name = "{0} holds one offset all year")
        @ValueSource(strings = {"DXB", "AUH", "DOH", "DEL", "BOM", "SIN", "HKG", "JNB", "NBO", "IST"})
        @DisplayName("the non-observing airports keep a single offset all year")
        void nonObservingAirportsNeverShift(String airportCode) {
            assertThat(offsetAt(airportCode, JANUARY)).isEqualTo(offsetAt(airportCode, JULY));
            // No ongoing DST rule at all (these zones do have historical
            // transitions, so isFixedOffset() is not the right question).
            assertThat(AirportTimeZones.zoneOf(airportCode).getRules().getTransitionRules()).isEmpty();
            assertThat(AirportTimeZones.zoneOf(airportCode).getRules()
                    .isDaylightSavings(JULY.toInstant(ZoneOffset.UTC))).isFalse();
        }

        @ParameterizedTest(name = "{0} still runs a DST rule")
        @ValueSource(strings = {"LHR", "MAN", "BHX", "EDI", "GLA", "CDG", "FRA", "JFK", "ATL", "SYD"})
        @DisplayName("the observing airports carry a live DST rule, so offsets must be resolved per instant")
        void observingAirportsCarryALiveDstRule(String airportCode) {
            assertThat(AirportTimeZones.zoneOf(airportCode).getRules().getTransitionRules()).isNotEmpty();
            assertThat(offsetAt(airportCode, JANUARY)).isNotEqualTo(offsetAt(airportCode, JULY));
        }

        @Test
        @DisplayName("the London spring-forward instant is a real gap in local time")
        void theSpringForwardGapExists() {
            ZoneId london = AirportTimeZones.zoneOf("LHR");
            // 2026-03-29 01:00 UK clocks jump to 02:00; 01:30 simply does not exist.
            LocalDateTime insideTheGap = LocalDateTime.of(2026, 3, 29, 1, 30);
            assertThat(london.getRules().getValidOffsets(insideTheGap)).isEmpty();
            assertThat(ZonedDateTime.of(insideTheGap, london).getHour()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("zoneOf: unknown, null and oddly-cased input")
    class Fallback {

        @Test
        @DisplayName("a null airport code falls back to UTC instead of throwing")
        void nullCodeFallsBackToUtc() {
            assertThat(AirportTimeZones.zoneOf(null)).isEqualTo(ZoneOffset.UTC);
        }

        @ParameterizedTest(name = "\"{0}\" -> UTC")
        @ValueSource(strings = {"", " ", "   ", "ZZZ", "XYZ", "LHRX", "LH", "123", "Europe/London", "lhr "})
        @DisplayName("blank and unknown codes fall back to UTC - wrong by at most the old behaviour")
        void unknownOrBlankCodeFallsBackToUtc(String airportCode) {
            assertThat(AirportTimeZones.zoneOf(airportCode)).isEqualTo(ZoneOffset.UTC);
        }

        @ParameterizedTest(name = "\"{0}\" resolves like LHR")
        @ValueSource(strings = {"lhr", "Lhr", "lHr", "LHR"})
        @DisplayName("lookup is case-insensitive, so a lower-cased code still resolves")
        void lookupIsCaseInsensitive(String airportCode) {
            assertThat(AirportTimeZones.zoneOf(airportCode)).isEqualTo(ZoneId.of("Europe/London"));
        }

        @Test
        @DisplayName("surrounding whitespace is NOT trimmed - a padded code silently becomes UTC")
        void surroundingWhitespaceIsNotTrimmed() {
            // Documents current behaviour: callers must pass an already-trimmed code.
            assertThat(AirportTimeZones.zoneOf(" LHR")).isEqualTo(ZoneOffset.UTC);
            assertThat(AirportTimeZones.zoneOf("LHR ")).isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("the UTC fallback is stable across calls")
        void fallbackIsStable() {
            assertThat(AirportTimeZones.zoneOf("ZZZ")).isSameAs(AirportTimeZones.zoneOf("QQQ"));
        }
    }

    @Nested
    @DisplayName("nowAt: the wall clock at that airport")
    class NowAt {

        @Test
        @DisplayName("nowAt returns the local clock of the airport's zone")
        void nowAtMatchesTheAirportsOwnClock() {
            LocalDateTime delhi = AirportTimeZones.nowAt("DEL");
            LocalDateTime reference = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
            assertThat(Duration.between(delhi, reference).abs()).isLessThan(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("nowAt for an unknown airport is the server's UTC clock")
        void nowAtUnknownAirportIsUtc() {
            LocalDateTime unknown = AirportTimeZones.nowAt("ZZZ");
            assertThat(Duration.between(unknown, LocalDateTime.now(ZoneOffset.UTC)).abs())
                    .isLessThan(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("nowAt(null) does not throw - it degrades to the UTC clock")
        void nowAtNullIsUtc() {
            assertThat(Duration.between(AirportTimeZones.nowAt(null), LocalDateTime.now(ZoneOffset.UTC)).abs())
                    .isLessThan(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("two airports on different zones report clocks separated by their offset difference")
        void twoAirportsDisagreeByTheirOffsetDifference() {
            LocalDateTime delhi = AirportTimeZones.nowAt("DEL");
            LocalDateTime singapore = AirportTimeZones.nowAt("SIN");
            // SIN is UTC+8, DEL is UTC+5:30 -> Singapore's wall clock reads 2h30m later.
            assertThat(Duration.between(delhi, singapore).toMinutes()).isBetween(149L, 151L);

            LocalDateTime newYork = AirportTimeZones.nowAt("JFK");
            // JFK trails Delhi by 9h30m (winter) or 10h30m (summer) - never zero.
            assertThat(Duration.between(newYork, delhi).toMinutes()).isBetween(569L, 631L);
        }
    }

    @Nested
    @DisplayName("the class itself")
    class ClassContract {

        @Test
        @DisplayName("AirportTimeZones is a final, non-instantiable utility holder")
        void isAFinalUtilityClass() throws Exception {
            assertThat(Modifier.isFinal(AirportTimeZones.class.getModifiers())).isTrue();
            Constructor<AirportTimeZones> constructor = AirportTimeZones.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }
    }
}
