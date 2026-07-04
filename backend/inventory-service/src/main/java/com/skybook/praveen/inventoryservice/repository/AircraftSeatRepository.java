package com.skybook.praveen.inventoryservice.repository;

import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AircraftSeatRepository extends JpaRepository<AircraftSeat, Long> {

    List<AircraftSeat> findByAircraftIdOrderByRowNumberAscSeatNumberAsc(Long aircraftId);

    Optional<AircraftSeat> findByAircraftIdAndSeatNumber(Long aircraftId, String seatNumber);

    List<AircraftSeat> findByAircraftIdAndStatus(Long aircraftId, AircraftSeatStatus status);

    long countByAircraftIdAndStatus(Long aircraftId, AircraftSeatStatus status);
}
