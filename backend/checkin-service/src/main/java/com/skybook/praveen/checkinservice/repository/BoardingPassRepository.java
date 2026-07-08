package com.skybook.praveen.checkinservice.repository;

import com.skybook.praveen.checkinservice.entity.BoardingPass;
import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardingPassRepository extends JpaRepository<BoardingPass, Long> {

    Optional<BoardingPass> findByBoardingPassNumber(String boardingPassNumber);

    Optional<BoardingPass> findByToken(String token);

    // The current live pass for a CheckIn - at most one ACTIVE row exists
    // per CheckIn at a time (service-layer guarantee, design doc section 3.2).
    Optional<BoardingPass> findByCheckInIdAndStatus(Long checkInId, BoardingPassStatus status);

    List<BoardingPass> findByCheckInIdOrderByIssuedAtAsc(Long checkInId);
}
