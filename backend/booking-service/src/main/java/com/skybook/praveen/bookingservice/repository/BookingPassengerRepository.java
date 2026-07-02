package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingPassengerRepository extends JpaRepository<BookingPassenger, Long> {

    // The real backstop against concurrently double-booking a seat (docs section 6) -
    // paired with the DB-level unique constraint on (flight_id, seat_number).
    boolean existsByFlightIdAndSeatNumber(Long flightId, String seatNumber);

    List<BookingPassenger> findByBooking_Id(Long bookingId);

    // {passengerId} in the /bookings/{id}/passengers/{passengerId}/... routes
    // refers to this entity's id (the passenger's line item within this
    // specific booking), not Passenger.id - see BookingController.
    Optional<BookingPassenger> findByIdAndBooking_Id(Long id, Long bookingId);
}
