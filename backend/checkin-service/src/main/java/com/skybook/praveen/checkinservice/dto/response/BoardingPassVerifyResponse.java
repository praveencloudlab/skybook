package com.skybook.praveen.checkinservice.dto.response;

/**
 * Result of a successful GET /api/boarding-passes/verify (design doc
 * section 6/7) - enough context for a gate reader to display without a
 * second lookup. Verification failures (tampered/unknown/revoked/already-
 * boarded) throw BoardingPassVerificationException -> 422, same "request
 * was valid, the real-world check failed" distinction payment-service uses
 * for gateway declines, rather than a valid=false field on a 200 body.
 */
public record BoardingPassVerifyResponse(

        String passengerName,

        String bookingReference,

        String flightNumber,

        String seatNumber,

        String gate,

        String boardingGroup

) {
}
