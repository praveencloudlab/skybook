package com.skybook.praveen.flightservice.scheduler;

import com.skybook.praveen.flightservice.service.FlightScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

/**
 * The rolling-window logic lives in FlightScheduleService.generateFlightsFor-
 * AllActiveSchedules() and is covered by FlightScheduleServiceImplTest - this
 * only verifies the nightly job delegates to it.
 */
@ExtendWith(MockitoExtension.class)
class FlightGenerationJobTest {

    @Mock
    private FlightScheduleService flightScheduleService;

    @InjectMocks
    private FlightGenerationJob job;

    @Test
    void delegatesTheNightlySweepToTheService() {
        job.generateUpcomingFlights();

        verify(flightScheduleService).generateFlightsForAllActiveSchedules();
    }

    @Test
    void aSweepWithNothingToDoStaysQuiet() {
        assertThatCode(() -> job.generateUpcomingFlights()).doesNotThrowAnyException();
    }
}
