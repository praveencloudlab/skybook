package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.BookingContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingContactRepository extends JpaRepository<BookingContact, Long> {

    Optional<BookingContact> findByBooking_Id(Long bookingId);

    List<BookingContact> findByContactEmailIgnoreCase(String contactEmail);
}
