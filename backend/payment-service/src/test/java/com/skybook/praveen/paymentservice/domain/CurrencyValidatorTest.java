package com.skybook.praveen.paymentservice.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyValidatorTest {

    private final CurrencyValidator validator = new CurrencyValidator("USD,GBP,EUR,INR");

    @Test
    void supportedCurrenciesPassCaseInsensitively() {
        assertThatCode(() -> validator.validate("USD")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("gbp")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("Inr")).doesNotThrowAnyException();
    }

    @Test
    void unsupportedCurrencyIsRejectedWithTheWhitelistInTheMessage() {
        assertThatThrownBy(() -> validator.validate("JPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPY")
                .hasMessageContaining("USD");
    }

    @Test
    void nullCurrencyIsRejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whitelistConfigToleratesWhitespace() {
        CurrencyValidator spaced = new CurrencyValidator(" usd , eur ");

        assertThatCode(() -> spaced.validate("USD")).doesNotThrowAnyException();
        assertThatCode(() -> spaced.validate("EUR")).doesNotThrowAnyException();
    }
}
