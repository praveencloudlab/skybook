package com.skybook.praveen.checkinservice.dto.response;

/**
 * Result of GET /api/boarding-passes/verify (design doc section 6) - the
 * gate reader's expected shape: pass/fail plus enough context to display,
 * without requiring a second lookup.
 */
public record BoardingPassVerifyResponse(

        boolean valid,

        // Populated on failure: TAMPERED_TOKEN, UNKNOWN_PASS, REVOKED,
        // ALREADY_BOARDED. Null when valid.
        String reason,

        String passengerName,

        String bookingReference,

        String flightNumber,

        String seatNumber,

        String gate,

        String boardingGroup

) {
}
