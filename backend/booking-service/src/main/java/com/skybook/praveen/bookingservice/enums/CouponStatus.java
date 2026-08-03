package com.skybook.praveen.bookingservice.enums;

/**
 * One flight coupon's lifecycle (ROUND_TRIP_MODULE.md, tickets & coupons):
 * a coupon is a passenger's right to fly ONE segment. OPEN -> CHECKED_IN via
 * the check-in mirror; CANCELLED/REFUNDED via the cancellation matrix. FLOWN
 * is reserved for a future departure sweep - the frontend derives "flown"
 * from the flight's departure time it already has.
 */
public enum CouponStatus {
    OPEN,
    CHECKED_IN,
    FLOWN,
    CANCELLED,
    REFUNDED
}
