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

    private List<BookingEventPassenger> passengers;

    private BigDecimal totalFare;

    private String currency;

    /** Payment status, e.g. "PAID" - null if no payment record yet */
    private String paymentStatus;
}