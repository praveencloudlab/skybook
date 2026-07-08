package com.skybook.praveen.checkinservice.repository;

import com.skybook.praveen.checkinservice.entity.Baggage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BaggageRepository extends JpaRepository<Baggage, Long> {

    List<Baggage> findByCheckInId(Long checkInId);

    boolean existsByTagNumber(String tagNumber);
}
