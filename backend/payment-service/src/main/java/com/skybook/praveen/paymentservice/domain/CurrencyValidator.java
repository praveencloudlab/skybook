package com.skybook.praveen.paymentservice.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ISO-4217 whitelist from configuration (payment.supported-currencies).
 * Currencies are validated, never converted - a booking pays in its own
 * currency (design doc section 15).
 */
@Component
public class CurrencyValidator {

    private final Set<String> supportedCurrencies;

    public CurrencyValidator(@Value("${payment.supported-currencies:USD}") String supported) {
        this.supportedCurrencies = Arrays.stream(supported.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String currency) {
        if (currency == null || !supportedCurrencies.contains(currency.toUpperCase())) {
            throw new IllegalArgumentException("Unsupported currency: " + currency
                    + " - supported: " + supportedCurrencies);
        }
    }
}
