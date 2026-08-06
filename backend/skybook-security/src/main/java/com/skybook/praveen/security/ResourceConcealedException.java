package com.skybook.praveen.security;

/**
 * Thrown when a GUEST session touches a resource outside its one booking
 * (GUEST_CHECKIN_MODULE.md §3.3, decision D8). Services map it to {@code 404},
 * never {@code 403}: for an account holder a 403 on a foreign id reveals
 * nothing (ids are opaque), but for a guest a 403-vs-404 split on
 * reference-derived resources would be an existence oracle for bookings.
 * Outside a guest's scope, other bookings do not exist.
 */
public class ResourceConcealedException extends RuntimeException {

    public ResourceConcealedException() {
        super("resource not found");
    }
}
