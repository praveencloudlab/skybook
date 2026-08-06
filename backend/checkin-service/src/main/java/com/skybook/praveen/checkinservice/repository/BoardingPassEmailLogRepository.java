package com.skybook.praveen.checkinservice.repository;

import com.skybook.praveen.checkinservice.entity.BoardingPassEmailLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface BoardingPassEmailLogRepository extends JpaRepository<BoardingPassEmailLog, Long> {

    long countByCheckInIdAndSentAtAfter(Long checkInId, Instant windowStart);
}
