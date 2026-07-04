package com.skybook.praveen.paymentservice.domain;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Year;

/**
 * "INV-2026-000123" - backed by a database sequence, NOT max+1, so
 * concurrent captures can never mint the same number (design doc section 7).
 * The one domain class allowed an infrastructure dependency: a sequence is
 * not business logic worth faking.
 *
 * One global sequence with a year prefix for readability; per-year reset is
 * deferred (documented).
 */
@Component
@RequiredArgsConstructor
public class InvoiceNumberGenerator {

    private static final String SEQUENCE = "invoice_number_seq";

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void ensureSequenceExists() {
        // ddl-auto: update doesn't know about sequences we don't map to ids.
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS " + SEQUENCE + " START WITH 1");
    }

    public String next() {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('" + SEQUENCE + "')", Long.class);
        return "INV-%d-%06d".formatted(Year.now().getValue(), value);
    }
}
