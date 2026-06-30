package com.skybook.praveen.flightservice.scheduler;

import com.skybook.praveen.flightservice.service.FlightScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlightGenerationJob {

    private final FlightScheduleService flightScheduleService;

    /**
     * Runs daily at 01:00 server time and rolls every ACTIVE schedule's
     * generated-flight window forward, each by its own generationDaysAhead.
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void generateUpcomingFlights() {
        log.info("Running scheduled flight generation job");
        flightScheduleService.generateFlightsForAllActiveSchedules();
        log.info("Scheduled flight generation job complete");
    }
}
