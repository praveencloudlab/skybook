package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.BookingPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingPaymentRepository extends JpaRepository<BookingPayment, Long> {

    Optional<BookingPayment> findByBooking_Id(Long bookingId);
}
