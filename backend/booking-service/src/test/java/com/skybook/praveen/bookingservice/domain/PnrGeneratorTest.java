package com.skybook.praveen.bookingservice.domain;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PnrGeneratorTest {

    // SB + 4 chars from the reduced alphabet (excludes 0, O, 1, I, L).
    private static final Pattern PNR_PATTERN = Pattern.compile("^SB[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$");

    private final PnrGenerator pnrGenerator = new PnrGenerator();

    @RepeatedTest(50)
    void generatesCandidateMatchingExpectedFormat() {
        String candidate = pnrGenerator.generateCandidate();
        assertThat(candidate).matches(PNR_PATTERN);
    }

    @RepeatedTest(50)
    void neverContainsAmbiguousCharacters() {
        String candidate = pnrGenerator.generateCandidate();
        assertThat(candidate).doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    void generatesDifferentCandidatesAcrossManyCalls() {

        Set<String> candidates = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            candidates.add(pnrGenerator.generateCandidate());
        }

        // Not a strict uniqueness guarantee (that's the repository retry
        // loop's job), but 200 draws from a ~1M-combination space should
        // produce well over a handful of distinct values.
        assertThat(candidates.size()).isGreaterThan(150);
    }
}
