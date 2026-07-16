package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingPassengerRepository extends JpaRepository<BookingPassenger, Long> {

    // NOTE: the old existsByFlightIdAndSeatNumber live-availability pre-check is
    // retired (SEAT_SELECTION_MODULE.md §2.6, round 7) - inventory-service's
    // hold under the shared flight lock is the sole live-seat exclusivity gate;
    // this table is a historical snapshot.

    List<BookingPassenger> findByBooking_Id(Long bookingId);

    // {passengerId} in the /bookings/{id}/passengers/{passengerId}/... routes
    // refers to this entity's id (the passenger's line item within this
    // specific booking), not Passenger.id - see BookingController.
    Optional<BookingPassenger> findByIdAndBooking_Id(Long id, Long bookingId);
}
