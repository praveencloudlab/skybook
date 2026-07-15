package com.skybook.praveen.inventoryservice.repository;

import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FlightInventoryRepository extends JpaRepository<FlightInventory, Long> {

    Optional<FlightInventory> findByFlightId(Long flightId);

    /**
     * SELECT ... FOR UPDATE on the flight's inventory row (SEAT_SELECTION_MODULE.md
     * §5). Every counter-mutating hold operation - manual AND auto - serializes on
     * this pessimistic lock, so two racing holds can't oversell or pick the same
     * seat. Reads keep using {@link #findByFlightId}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select fi from FlightInventory fi where fi.flightId = :flightId")
    Optional<FlightInventory> findByFlightIdForUpdate(@Param("flightId") Long flightId);

    boolean existsByFlightId(Long flightId);

    List<FlightInventory> findByStatus(InventoryStatus status);

    List<FlightInventory> findByAircraftId(Long aircraftId);
}
