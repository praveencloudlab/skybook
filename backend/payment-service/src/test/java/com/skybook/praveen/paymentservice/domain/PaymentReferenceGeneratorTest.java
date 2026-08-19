package com.skybook.praveen.paymentservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReferenceGeneratorTest {

    private final PaymentReferenceGenerator generator = new PaymentReferenceGenerator();

    private static final String SUFFIX_PATTERN = "[A-HJ-NP-Z2-9]{6}";

    @Test
    void referencesFollowThePnrPhilosophyFormat() {
        int year = Year.now().getValue();

        assertThat(generator.paymentReference()).matches("PAY-" + year + "-" + SUFFIX_PATTERN);
        assertThat(generator.transactionReference()).matches("TXN-" + year + "-" + SUFFIX_PATTERN);
        assertThat(generator.refundReference()).matches("REF-" + year + "-" + SUFFIX_PATTERN);
    }

    @Test
    void ambiguousCharactersNeverAppear() {
        for (int i = 0; i < 500; i++) {
            String suffix = generator.paymentReference().substring(9);
            assertThat(suffix).doesNotContain("0").doesNotContain("O")
                    .doesNotContain("1").doesNotContain("I");
        }
    }

    @Test
    void collisionsAreRareAcrossManyGenerations() {
        // 32^6 = ~1.07 billion combinations. Collisions among 10k random
        // draws are rare but NOT impossible: the birthday bound expects
        // ~0.047 of them, so demanding zero fails a perfectly healthy
        // generator about one run in 22 - which is exactly how this test
        // sank an untouched CI run. Allowing 3 makes the pass deterministic
        // in practice (P[more than 3] ~ 4e-7) while still failing loudly if
        // the entropy ever degrades - a space 100x smaller expects ~5
        // collisions per 10k and would trip this almost every run.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.paymentReference());
        }
        assertThat(seen.size()).isGreaterThanOrEqualTo(10_000 - 3);
    }
}
