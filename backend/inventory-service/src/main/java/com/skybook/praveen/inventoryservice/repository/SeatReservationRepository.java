package com.skybook.praveen.inventoryservice.repository;

import com.skybook.praveen.inventoryservice.entity.SeatReservation;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeatReservationRepository extends JpaRepository<SeatReservation, Long> {

    List<SeatReservation> findByBookingId(Long bookingId);

    List<SeatReservation> findByBookingIdAndStatus(Long bookingId, SeatReservationStatus status);

    Optional<SeatReservation> findByFlightInventoryIdAndAircraftSeatIdAndStatus(
            Long flightInventoryId, Long aircraftSeatId, SeatReservationStatus status);

    boolean existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
            Long flightInventoryId, Long aircraftSeatId, SeatReservationStatus status);

    List<SeatReservation> findByFlightInventoryIdAndStatus(
            Long flightInventoryId, SeatReservationStatus status);

    long countByFlightInventoryIdAndStatus(Long flightInventoryId, SeatReservationStatus status);
}
