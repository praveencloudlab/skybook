package com.skybook.praveen.checkinservice.enums;

/** String enum recorded on CheckInHistory - see docs/CHECKIN_SERVICE_MODULE.md section 3.4. */
public enum CheckInHistoryType {

    // Not in the design doc's original list (section 3.4/4.4) - added
    // because every sibling aggregate (Payment, Booking) records a
    // *_CREATED entry at creation and CheckIn's creation is equally worth
    // auditing (was it API-created or BookingEvent-driven).
    CHECKIN_CREATED,

    CHECKIN_OPENED,

    CHECKED_IN,

    BOARDED,

    NO_SHOW,

    CANCELLED,

    SEAT_CHANGED,

    BAGGAGE_ADDED,

    BOARDING_PASS_ISSUED,

    BOARDING_PASS_REVOKED
}
