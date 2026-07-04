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
        // 32^6 = ~1.07 billion combinations - 10k draws must not collide.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.paymentReference());
        }
        assertThat(seen).hasSize(10_000);
    }
}
