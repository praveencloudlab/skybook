package com.skybook.praveen.bookingservice.enums;

/**
 * Lifecycle of the booking itself (the PNR), independent of {@link PaymentStatus}
 * and {@link CheckInStatus} - see docs/BOOKING_SERVICE_MODULE.md section 4.
 */
public enum BookingStatus {

    /**
     * Committed but not yet finalized (SEAT_SELECTION_MODULE.md §5.1a): the
     * draft -> hold -> finalize flow needs booking/passenger IDs before it can
     * take inventory holds, so the draft commits with seat_number NULL and NO
     * payment row. Only DRAFT -> CREATED (finalize) and DRAFT -> CANCELLED
     * (failure/sweep) are legal - a crash-orphaned draft can never be confirmed.
     */
    DRAFT,

    CREATED,

    CONFIRMED,

    /**
     * At least one passenger cancelled, at least one still active (passenger-
     * cancellation business rules 9-11). Derived from passenger states, never set
     * by hand; the booking stays valid and its remaining passengers travel.
     */
    PARTIALLY_CANCELLED,

    CANCELLED,

    COMPLETED
}
