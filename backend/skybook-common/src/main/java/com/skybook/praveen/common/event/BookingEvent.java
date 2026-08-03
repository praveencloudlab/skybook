package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingEvent {

    /**
     * Event Type
     */
    private BookingEventType type;

    /**
     * Booking Reference (PNR)
     */
    private String bookingReference;

    /**
     * Recipient Email
     */
    private String contactEmail;

    /**
     * Recipient Name
     */
    private String contactName;

    /**
     * Email Subject
     */
    private String subject;

    /**
     * Email Body (plain-text fallback - notification-service renders the
     * HTML template from the structured fields below when they are present)
     */
    private String message;

    // -----------------------------------------------------------------
    // Structured booking details (nullable - additive, older events without
    // them still deserialize and fall back to the plain message above)
    // -----------------------------------------------------------------

    /** Numeric booking id - correlation key for payment-service (unique per payment) */
    private Long bookingId;

    /** Booking status after this event, e.g. "CONFIRMED" */
    private String bookingStatus;

    /** Flight id in flight-service (route/times enrichment is future work) */
    private Long flightId;

    /** Booking date, pre-formatted, e.g. "2026-07-04 02:26" */
    private String bookingDate;

    // Flight context (nullable - populated best-effort from flight-service
    // for the email template; older events simply lack it).
    private String flightNumber;

    private String originAirportCode;

    private String destinationAirportCode;

    /** Pre-formatted, e.g. "2026-07-08 21:25" */
    private String departureTime;

    private String arrivalTime;

    private List<BookingEventPassenger> passengers;

    /**
     * The journey's legs with their passengers nested (ROUND_TRIP_MODULE.md
     * §6). When present, consumers MUST prefer this over the top-level
     * flight fields + flat passengers list, which are kept exactly one
     * release as a deprecated segment-0 mirror for old consumers and
     * replayed old events (null segments = old event, use the fallback).
     */
    private List<BookingEventSegment> segments;

    private BigDecimal totalFare;

    /**
     * CANCELLED events only: the time-tier refund percent quoted at
     * cancellation (booking-service CancellationPolicy) - 100 = fare rules
     * alone, 50 = half, 0 = same-day forfeiture (payment-service must NOT
     * create a refund). Null on legacy events = 100 (old behaviour).
     */
    private Integer refundTierPercent;

    /**
     * PARTIALLY_CANCELLED only: the cancelled rows' fares in payment-service's
     * compact breakdown format ("FLEXI:100.00;SAVER:80.00") - exactly what
     * RefundCalculator parses, so payment refunds those lines (scaled by
     * refundTierPercent) instead of the whole remaining capture.
     */
    private String refundBreakdown;

    /**
     * PARTIALLY_CANCELLED only: the BookingPassenger row ids THIS event
     * cancelled - checkin-service closes exactly those check-ins.
     */
    private List<Long> cancelledBookingPassengerIds;

    private String currency;

    /** Payment status, e.g. "PAID" - null if no payment record yet */
    private String paymentStatus;

    /**
     * The booking owner's JWT subject (SECURITY_HARDENING_MODULE.md §4.2),
     * captured at booking creation. Rides every event type (CREATED/CONFIRMED/
     * CANCELLED) so payment-service and check-in-service can snapshot it and
     * enforce object-level ownership. Null on legacy/pre-branch events.
     */
    private String ownerSubject;
}