package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    boolean existsByBookingReference(String bookingReference);

    List<Booking> findByFlightId(Long flightId);

    List<Booking> findByCustomerId(Long customerId);

    List<Booking> findByBookingStatus(BookingStatus bookingStatus);
}
