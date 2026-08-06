package com.skybook.praveen.bookingservice.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The surname comparison table (GUEST_CHECKIN_MODULE.md §6). Real surnames
 * carry apostrophes, hyphens, diacritics and spacing that no passenger should
 * be locked out of check-in for - and none of which should widen who matches.
 */
class SurnameNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "O'Brien,    obrien",
            "O'Brien,    O BRIEN",
            "O'Brien,    Óbrien",
            "García-López, Garcia Lopez",
            "García-López, GARCIALOPEZ",
            "van der Berg, VanDerBerg",
    })
    void equivalentSpellingsMatch(String stored, String typed) {
        assertThat(SurnameNormalizer.matches(stored, typed)).isTrue();
    }

    @Test
    void diacriticStripsToTheBareLetterNotTheTransliteration() {
        // ü strips to u; the German ue convention is a different
        // TRANSLITERATION, not a diacritic, and must not match.
        assertThat(SurnameNormalizer.matches("Müller", "Muller")).isTrue();
        assertThat(SurnameNormalizer.matches("Müller", "Mueller")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "O'Brien,  OBrian",
            "Smith,    Smyth",
            "Patel,    Pate",
    })
    void differentNamesStayDifferent(String stored, String typed) {
        assertThat(SurnameNormalizer.matches(stored, typed)).isFalse();
    }

    @Test
    void emptinessMatchesNothingEver() {
        // A stored name that normalizes to nothing (or a blank guess) must
        // fail - '' == '' would otherwise unlock rows with degenerate data.
        assertThat(SurnameNormalizer.matches("", "")).isFalse();
        assertThat(SurnameNormalizer.matches(null, null)).isFalse();
        assertThat(SurnameNormalizer.matches("---", "---")).isFalse();
        assertThat(SurnameNormalizer.matches("Smith", "")).isFalse();
    }
}
