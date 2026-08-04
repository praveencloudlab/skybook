package com.skybook.praveen.checkinservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BoardingPassNumberGeneratorTest {

    private final BoardingPassNumberGenerator generator = new BoardingPassNumberGenerator();

    private static final String SUFFIX_PATTERN = "[A-HJ-NP-Z2-9]{6}";

    @Test
    void followsThePnrPhilosophyFormat() {
        int year = Year.now().getValue();
        assertThat(generator.generate()).matches("BP-" + year + "-" + SUFFIX_PATTERN);
    }

    @Test
    void ambiguousCharactersNeverAppear() {
        for (int i = 0; i < 500; i++) {
            String suffix = generator.generate().substring(8);
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

        // Rare, which is what the generator promises - not impossible, which is
        // what this used to assert. The suffix is six characters from a
        // 32-symbol alphabet, so 32^6 is ~1.07 billion codes; drawing 10,000 of
        // them collides about once every twenty-two runs by the birthday
        // paradox alone. Demanding all 10,000 be distinct therefore failed a
        // few times a year for no reason anyone could reproduce, which is worse
        // than useless in CI. A handful of duplicates is the expected shape; a
        // real regression - a shrunken alphabet, a seeded Random, a truncated
        // suffix - collapses the count far below this floor.
        assertThat(seen).hasSizeGreaterThanOrEqualTo(9_990);
    }
}
