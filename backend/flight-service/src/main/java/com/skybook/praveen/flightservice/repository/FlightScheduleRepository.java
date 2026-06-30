package com.skybook.praveen.flightservice.repository;

import com.skybook.praveen.flightservice.entity.FlightSchedule;
import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlightScheduleRepository extends JpaRepository<FlightSchedule, Long> {

    List<FlightSchedule> findByStatus(ScheduleStatus status);
}
