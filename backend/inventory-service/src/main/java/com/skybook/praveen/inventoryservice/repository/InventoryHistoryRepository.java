package com.skybook.praveen.inventoryservice.repository;

import com.skybook.praveen.inventoryservice.entity.InventoryHistory;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryHistoryRepository extends JpaRepository<InventoryHistory, Long> {

    List<InventoryHistory> findByFlightInventoryIdOrderByChangedAtAsc(Long flightInventoryId);

    List<InventoryHistory> findByBookingId(Long bookingId);

    List<InventoryHistory> findByFlightInventoryIdAndHistoryType(
            Long flightInventoryId, InventoryHistoryType historyType);
}
