package com.skybook.praveen.checkinservice.repository;

import com.skybook.praveen.checkinservice.entity.FlightManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FlightManifestRepository extends JpaRepository<FlightManifest, Long> {

    Optional<FlightManifest> findByFlightId(Long flightId);
}
