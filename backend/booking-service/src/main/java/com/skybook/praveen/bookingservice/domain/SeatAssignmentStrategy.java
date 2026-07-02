package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.entity.BookingPassenger;

/**
 * Resolves the seat number for a passenger being added to a booking.
 *
 * Deliberately just an interface with a manual/validate-only implementation
 * for v1 (docs sections 9/11) - a real assignment algorithm (auto-picking
 * from a seat map) is premature before there's an actual seat map to pick
 * from, which is Inventory Service's job. Availability/double-booking
 * checks are NOT this strategy's job either - those live in
 * BookingServiceImpl + the DB unique constraint (docs section 6), since
 * they inherently require a repository lookup and this interface is meant
 * to stay pure.
 */
public interface SeatAssignmentStrategy {

    String resolveSeatNumber(BookingPassenger passenger, String requestedSeatNumber);
}
