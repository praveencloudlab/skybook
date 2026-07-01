package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.BookingHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingHistoryRepository extends JpaRepository<BookingHistory, Long> {

    List<BookingHistory> findByBooking_IdOrderByChangedAtAsc(Long bookingId);
}
