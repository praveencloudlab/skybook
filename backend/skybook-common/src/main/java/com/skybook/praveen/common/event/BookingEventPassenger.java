package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One traveler inside a BookingEvent - used by notification-service's email template. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingEventPassenger {

    /**
     * BookingPassenger.id in booking-service - added for checkin-service,
     * which keys CheckIn's uniqueness constraint on this (docs/
     * CHECKIN_SERVICE_MODULE.md section 3.1). Nullable: older/pre-
     * enrichment events don't carry it - checkin-service's consumer skips
     * those loudly, same precedent as BookingEvent.bookingId being added
     * for payment-service.
     */
    private Long bookingPassengerId;

    private String name;

    private String seatNumber;

    private String travelClass;

    private String fareType;

    private BigDecimal fare;

    /**
     * The seat surcharge the passenger actually PAID at booking (0 for an
     * AUTO-assigned seat) - SEAT_SELECTION_MODULE.md §9: checkin-service
     * snapshots this as the passenger's free-seat-change entitlement ceiling.
     * Nullable: pre-seat-selection events don't carry it - consumers treat
     * null as 0 (only free seats reachable at check-in).
     */
    private BigDecimal seatSurcharge;

    /** ISO-4217 of fare/seatSurcharge ("USD" in v1). Nullable on legacy events. */
    private String currency;

    /** e.g. "NOT_OPEN", "CHECKED_IN" - snapshot at event time */
    private String checkInStatus;
}
