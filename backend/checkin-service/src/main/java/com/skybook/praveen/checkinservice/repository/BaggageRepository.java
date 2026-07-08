package com.skybook.praveen.checkinservice.repository;

import com.skybook.praveen.checkinservice.entity.Baggage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BaggageRepository extends JpaRepository<Baggage, Long> {

    List<Baggage> findByCheckInId(Long checkInId);

    // Manifest baggage count/weight (design doc section 3.5/5.7) - traverses
    // the checkIn relationship to flightId.
    List<Baggage> findByCheckInFlightId(Long flightId);

    boolean existsByTagNumber(String tagNumber);
}
