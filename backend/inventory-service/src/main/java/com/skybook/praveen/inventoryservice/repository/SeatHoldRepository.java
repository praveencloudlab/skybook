package com.skybook.praveen.inventoryservice.repository;

import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    List<SeatHold> findByBookingId(Long bookingId);

    List<SeatHold> findByBookingIdAndStatus(Long bookingId, SeatHoldStatus status);

    Optional<SeatHold> findByFlightInventoryIdAndAircraftSeatIdAndStatus(
            Long flightInventoryId, Long aircraftSeatId, SeatHoldStatus status);

    boolean existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
            Long flightInventoryId, Long aircraftSeatId, SeatHoldStatus status);

    // Expiry-job scan: every ACTIVE hold whose TTL has passed.
    List<SeatHold> findByStatusAndExpiresAtBefore(SeatHoldStatus status, LocalDateTime cutoff);

    List<SeatHold> findByFlightInventoryIdAndStatus(Long flightInventoryId, SeatHoldStatus status);
}
