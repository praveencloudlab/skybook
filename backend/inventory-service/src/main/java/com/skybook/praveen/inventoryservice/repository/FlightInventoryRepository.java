package com.skybook.praveen.inventoryservice.repository;

import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FlightInventoryRepository extends JpaRepository<FlightInventory, Long> {

    Optional<FlightInventory> findByFlightId(Long flightId);

    boolean existsByFlightId(Long flightId);

    List<FlightInventory> findByStatus(InventoryStatus status);

    List<FlightInventory> findByAircraftId(Long aircraftId);
}
