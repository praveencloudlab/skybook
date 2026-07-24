package com.skybook.praveen.checkinservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BaggageTagGeneratorTest {

    private final BaggageTagGenerator generator = new BaggageTagGenerator();

    private static final String SUFFIX_PATTERN = "[A-HJ-NP-Z2-9]{6}";

    @Test
    void followsThePnrPhilosophyFormat() {
        int year = Year.now().getValue();
        assertThat(generator.generate()).matches("BAG-" + year + "-" + SUFFIX_PATTERN);
    }

    @Test
    void ambiguousCharactersNeverAppear() {
        for (int i = 0; i < 500; i++) {
            String suffix = generator.generate().substring(9);
            assertThat(suffix).doesNotContain("0").doesNotContain("O")
                    .doesNotContain("1").doesNotContain("I");
        }
    }

    @Test
    void collisionsAreRareAcrossManyGenerations() {
        // 6 chars from a 32-symbol alphabet = 32^6 ≈ 1.07e9 possible suffixes.
        // Over 10k draws the birthday-paradox collision probability is ~4.6%,
        // so demanding ZERO collisions makes this test flaky (~1 run in 20).
        // Assert what the name promises - collisions are RARE - with a bound
        // (>= 9990 unique, i.e. < 0.1%) that is astronomically safe yet still
        // catches a genuinely broken generator.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen.size()).isGreaterThanOrEqualTo(9_990);
    }
}
