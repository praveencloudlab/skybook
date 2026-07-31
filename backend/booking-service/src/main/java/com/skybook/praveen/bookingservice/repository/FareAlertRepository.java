package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.FareAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FareAlertRepository extends JpaRepository<FareAlert, Long> {

    List<FareAlert> findByOwnerSubjectAndActiveTrueOrderByTravelDateAsc(String ownerSubject);

    List<FareAlert> findByActiveTrue();
}
