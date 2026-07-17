package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    boolean existsByBookingReference(String bookingReference);

    List<Booking> findByFlightId(Long flightId);

    List<Booking> findByCustomerId(Long customerId);

    List<Booking> findByBookingStatus(BookingStatus bookingStatus);

    // Stale-draft sweep scan (SEAT_SELECTION_MODULE.md §5.1a): DRAFT bookings
    // whose TTL has passed - the booking-side analogue of expired seat holds.
    List<Booking> findByBookingStatusAndBookingDateBefore(BookingStatus status, LocalDateTime cutoff);
}
