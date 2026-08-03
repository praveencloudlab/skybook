package com.skybook.praveen.flightservice.entity;

import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The @PrePersist safety nets: a row inserted by any path that forgot to set a
 * status or a generation horizon still lands in the database with sane
 * defaults, and an explicitly chosen value is never overwritten.
 */
class EntityDefaultsTest {

    @Test
    void aFlightWithoutAStatusIsPersistedAsScheduled() {
        Flight flight = Flight.builder()
                .flightNumber("BA178")
                .airlineCode("BA")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(LocalDate.now().plusDays(1).atTime(10, 15))
                .arrivalTime(LocalDate.now().plusDays(1).atTime(13, 40))
                .build();

        flight.prePersist();

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    void anExplicitFlightStatusIsNotOverwritten() {
        Flight flight = Flight.builder().status(FlightStatus.CANCELLED).build();

        flight.prePersist();

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CANCELLED);
    }

    @Test
    void aScheduleWithoutAStatusOrHorizonGetsActiveAndThirtyDays() {
        FlightSchedule schedule = FlightSchedule.builder()
                .flightNumber("BA178")
                .airlineCode("BA")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(LocalTime.of(10, 15))
                .arrivalTime(LocalTime.of(13, 40))
                .operatingDays(EnumSet.of(DayOfWeek.MONDAY))
                .validFrom(LocalDate.now())
                .build();
        schedule.setGenerationDaysAhead(null);

        schedule.prePersist();

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);
        assertThat(schedule.getGenerationDaysAhead()).isEqualTo(30);
    }

    @Test
    void anExplicitScheduleStatusAndHorizonAreNotOverwritten() {
        FlightSchedule schedule = FlightSchedule.builder()
                .status(ScheduleStatus.PAUSED)
                .generationDaysAhead(90)
                .build();

        schedule.prePersist();

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PAUSED);
        assertThat(schedule.getGenerationDaysAhead()).isEqualTo(90);
        // The builder default keeps the collection non-null for JPA.
        assertThat(schedule.getOperatingDays()).isEmpty();
    }
}
