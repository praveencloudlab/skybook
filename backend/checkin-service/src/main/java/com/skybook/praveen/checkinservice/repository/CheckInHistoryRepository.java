package com.skybook.praveen.checkinservice.repository;

import com.skybook.praveen.checkinservice.entity.CheckInHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CheckInHistoryRepository extends JpaRepository<CheckInHistory, Long> {

    List<CheckInHistory> findByCheckInIdOrderByChangedAtAsc(Long checkInId);
}
