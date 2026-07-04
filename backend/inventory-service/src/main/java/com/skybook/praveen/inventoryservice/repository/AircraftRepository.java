package com.skybook.praveen.inventoryservice.repository;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {

    Optional<Aircraft> findByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumber(String registrationNumber);

    List<Aircraft> findByStatus(AircraftStatus status);
}
