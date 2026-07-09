package com.skybook.praveen.checkinservice.enums;

/**
 * Per-passenger-per-flight (lives on {@code CheckIn}, one row per
 * bookingPassengerId - not per booking, since two travelers on the same PNR
 * can be in different check-in states).
 *
 * NO_SHOW means "didn't fly," reachable from any pre-boarding state, not
 * only CHECKED_IN - see docs/CHECKIN_SERVICE_MODULE.md section 4.1 for why
 * this deliberately diverges from a literal reading of the original brief
 * (no separate CLOSED state; "checked in but vanished" vs "never checked
 * in" is answerable from CheckInHistory/checkedInAt, not a distinct value).
 */
public enum CheckInStatus {

    NOT_OPEN,

    OPEN,

    CHECKED_IN,

    BOARDED,

    COMPLETED,

    NO_SHOW,

    CANCELLED
}
