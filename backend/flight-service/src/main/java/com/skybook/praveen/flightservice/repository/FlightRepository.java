package com.skybook.praveen.flightservice.repository;

import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    Optional<Flight> findByFlightNumber(String flightNumber);

    boolean existsByFlightNumber(String flightNumber);

    boolean existsByFlightNumberAndDepartureTime(
            String flightNumber,
            LocalDateTime departureTime
    );

    List<Flight> findByStatus(FlightStatus status);

    List<Flight> findByDepartureTimeBetween(
            LocalDateTime start,
            LocalDateTime end
    );

    List<Flight> findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
            String originAirportCode,
            String destinationAirportCode,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    );

    List<Flight> findByScheduleIdAndDepartureTimeAfter(
            Long scheduleId,
            LocalDateTime departureTime
    );
}
