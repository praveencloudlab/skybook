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
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(10_000);
    }
}
