package com.skybook.praveen.inventoryservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Binds inventory.seat-pricing.* (SEAT_SELECTION_MODULE.md §4). Listed
 * surcharges are config, not code: re-pricing is a config change, never a
 * data migration (surcharges are derived from seat attributes at read time -
 * except on a SeatHold, which snapshots them immutably at creation, §6).
 * All amounts USD (v1 single currency, §8).
 */
@ConfigurationProperties(prefix = "inventory.seat-pricing")
public class SeatPricingProperties {

    /** Exit-row tier (NOT "extra legroom" - that attribute doesn't exist yet, design §2.2). */
    private BigDecimal exitRow = new BigDecimal("30.00");

    /** First N rows of the seat's own cabin (N = frontRowCount below). */
    private BigDecimal frontOfCabin = new BigDecimal("15.00");

    private BigDecimal window = new BigDecimal("12.00");

    private BigDecimal aisle = new BigDecimal("8.00");

    /** Standard middle seats: the free auto-assign pool. */
    private BigDecimal middle = BigDecimal.ZERO;

    /** How many rows from each cabin's first row count as front-of-cabin. */
    private int frontRowCount = 2;

    public BigDecimal getExitRow() {
        return exitRow;
    }

    public void setExitRow(BigDecimal exitRow) {
        this.exitRow = exitRow;
    }

    public BigDecimal getFrontOfCabin() {
        return frontOfCabin;
    }

    public void setFrontOfCabin(BigDecimal frontOfCabin) {
        this.frontOfCabin = frontOfCabin;
    }

    public BigDecimal getWindow() {
        return window;
    }

    public void setWindow(BigDecimal window) {
        this.window = window;
    }

    public BigDecimal getAisle() {
        return aisle;
    }

    public void setAisle(BigDecimal aisle) {
        this.aisle = aisle;
    }

    public BigDecimal getMiddle() {
        return middle;
    }

    public void setMiddle(BigDecimal middle) {
        this.middle = middle;
    }

    public int getFrontRowCount() {
        return frontRowCount;
    }

    public void setFrontRowCount(int frontRowCount) {
        this.frontRowCount = frontRowCount;
    }
}
