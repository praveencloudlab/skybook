package com.skybook.praveen.bookingservice.security;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.exception.BookingNotFoundException;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import com.skybook.praveen.security.SecurityAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Object-level ownership for booking HTTP endpoints
 * (SECURITY_HARDENING_MODULE.md §4.2). Called at the CONTROLLER boundary only -
 * the payment-event consumer confirms bookings by calling the service method
 * directly on a Kafka thread (no SecurityContext) and must never hit this. A
 * USER may act only on their own booking; ADMIN/SERVICE on any; a legacy
 * null-owner row is privileged-only.
 */
@Component
@RequiredArgsConstructor
public class BookingAccessGuard {

    private final BookingRepository bookingRepository;

    public void requireOwnerOfBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        SecurityAccess.requireOwnerOrAdmin(booking.getOwnerSubject());
    }

    public void requireOwnerOfBookingByReference(String pnr) {
        Booking booking = bookingRepository.findByBookingReference(pnr)
                .orElseThrow(() -> new BookingNotFoundException(pnr));
        SecurityAccess.requireOwnerOrAdmin(booking.getOwnerSubject());
    }
}
