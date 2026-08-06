package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Internal request for a booking-scoped guest session token
 * (GUEST_CHECKIN_MODULE.md §3.1). The caller (booking-service, HTTP Basic on
 * the client-credential chain) has already verified reference + surname
 * against its own data; auth-service's part is the grant check and the mint.
 */
public record GuestTokenRequest(

        @NotNull(message = "bookingId is required")
        Long bookingId

) {
}
