package com.skybook.praveen.flightservice.mapper;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.request.CreateFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.request.UpdateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.FlightScheduleResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.entity.FlightSchedule;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both mappers, request -> entity -> response, including the fields the
 * mappers derive rather than copy (uppercasing, terminals, the default
 * generation horizon) and the nullable ones (no schedule, open-ended validTo,
 * un-audited rows).
 */
class FlightMappersTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);

    @Nested
    class FlightMapping {

        private final LocalDateTime departure = LocalDateTime.of(2026, 8, 10, 10, 15);
        private final LocalDateTime arrival = LocalDateTime.of(2026, 8, 10, 13, 40);

        private CreateFlightRequest createRequest() {
            return new CreateFlightRequest("ba178", "ba", "lhr", "jfk", departure, arrival);
        }

        @Test
        void toEntityUppercasesEveryCodeAndStartsAsScheduled() {
            Flight flight = FlightMapper.toEntity(createRequest());

            assertThat(flight.getFlightNumber()).isEqualTo("BA178");
            assertThat(flight.getAirlineCode()).isEqualTo("BA");
            assertThat(flight.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(flight.getDestinationAirportCode()).isEqualTo("JFK");
            assertThat(flight.getDepartureTime()).isEqualTo(departure);
            assertThat(flight.getArrivalTime()).isEqualTo(arrival);
            assertThat(flight.getStatus()).isEqualTo(FlightStatus.SCHEDULED);
            assertThat(flight.getId()).isNull();
            assertThat(flight.getSchedule()).isNull();
        }

        @Test
        void toEntityStampsTheCarriersRealTerminalsAtBothEnds() {
            Flight flight = FlightMapper.toEntity(createRequest());

            // BA is T5 at Heathrow and T8 at JFK - never invented downstream.
            assertThat(flight.getDepartureTerminal()).isEqualTo("5");
            assertThat(flight.getArrivalTerminal()).isEqualTo("8");
        }

        @Test
        void toResponseCarriesEveryFieldIncludingAuditColumns() {
            FlightSchedule schedule = FlightSchedule.builder().id(9L).build();
            Flight flight = Flight.builder()
                    .id(1L)
                    .flightNumber("BA178")
                    .airlineCode("BA")
                    .originAirportCode("LHR")
                    .destinationAirportCode("JFK")
                    .departureTime(departure)
                    .arrivalTime(arrival)
                    .departureTerminal("5")
                    .arrivalTerminal("8")
                    .status(FlightStatus.BOARDING)
                    .schedule(schedule)
                    .build();
            flight.setCreatedAt(NOW);
            flight.setUpdatedAt(NOW);
            flight.setCreatedBy("system");
            flight.setUpdatedBy("system");
            flight.setVersion(3L);

            FlightResponse response = FlightMapper.toResponse(flight);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.flightNumber()).isEqualTo("BA178");
            assertThat(response.airlineCode()).isEqualTo("BA");
            assertThat(response.originAirportCode()).isEqualTo("LHR");
            assertThat(response.destinationAirportCode()).isEqualTo("JFK");
            assertThat(response.departureTime()).isEqualTo(departure);
            assertThat(response.arrivalTime()).isEqualTo(arrival);
            assertThat(response.departureTerminal()).isEqualTo("5");
            assertThat(response.arrivalTerminal()).isEqualTo("8");
            assertThat(response.status()).isEqualTo(FlightStatus.BOARDING);
            assertThat(response.scheduleId()).isEqualTo(9L);
            assertThat(response.createdBy()).isEqualTo("system");
            assertThat(response.updatedBy()).isEqualTo("system");
            assertThat(response.version()).isEqualTo(3L);
            assertThat(response.createdAt()).isEqualTo(NOW);
            assertThat(response.updatedAt()).isEqualTo(NOW);
        }

        @Test
        void toResponseLeavesScheduleIdNullForManuallyCreatedFlights() {
            FlightResponse response = FlightMapper.toResponse(FlightMapper.toEntity(createRequest()));

            assertThat(response.scheduleId()).isNull();
            assertThat(response.id()).isNull();
            assertThat(response.version()).isNull();
            assertThat(response.createdAt()).isNull();
            assertThat(response.updatedAt()).isNull();
        }

        @Test
        void roundTripThroughEntityPreservesTheRequest() {
            FlightResponse response = FlightMapper.toResponse(FlightMapper.toEntity(createRequest()));

            assertThat(response.flightNumber()).isEqualTo("BA178");
            assertThat(response.originAirportCode()).isEqualTo("LHR");
            assertThat(response.destinationAirportCode()).isEqualTo("JFK");
            assertThat(response.departureTime()).isEqualTo(departure);
            assertThat(response.arrivalTime()).isEqualTo(arrival);
            assertThat(response.status()).isEqualTo(FlightStatus.SCHEDULED);
        }

        @Test
        void updateEntityUppercasesTheNewRouteAndLeavesIdentityAlone() {
            Flight flight = FlightMapper.toEntity(createRequest());
            LocalDateTime newDeparture = departure.plusDays(1);
            LocalDateTime newArrival = arrival.plusDays(1);

            FlightMapper.updateEntity(flight,
                    new UpdateFlightRequest("ek", "dxb", "sin", newDeparture, newArrival));

            assertThat(flight.getAirlineCode()).isEqualTo("EK");
            assertThat(flight.getOriginAirportCode()).isEqualTo("DXB");
            assertThat(flight.getDestinationAirportCode()).isEqualTo("SIN");
            assertThat(flight.getDepartureTime()).isEqualTo(newDeparture);
            assertThat(flight.getArrivalTime()).isEqualTo(newArrival);
            // Flight number and status are not part of an update.
            assertThat(flight.getFlightNumber()).isEqualTo("BA178");
            assertThat(flight.getStatus()).isEqualTo(FlightStatus.SCHEDULED);
        }
    }

    @Nested
    class FlightScheduleMapping {

        private final LocalTime departureTime = LocalTime.of(10, 15);
        private final LocalTime arrivalTime = LocalTime.of(13, 40);
        private final LocalDate validFrom = LocalDate.of(2026, 9, 1);
        private final LocalDate validTo = LocalDate.of(2026, 12, 31);
        private final Set<DayOfWeek> operatingDays =
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);

        private CreateFlightScheduleRequest request(LocalDate to, Integer horizon) {
            return new CreateFlightScheduleRequest(
                    "ba178", "ba", "lhr", "jfk",
                    departureTime, arrivalTime, operatingDays, validFrom, to, horizon);
        }

        @Test
        void toEntityUppercasesCodesAndStartsActive() {
            FlightSchedule schedule = FlightScheduleMapper.toEntity(request(validTo, 45));

            assertThat(schedule.getFlightNumber()).isEqualTo("BA178");
            assertThat(schedule.getAirlineCode()).isEqualTo("BA");
            assertThat(schedule.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(schedule.getDestinationAirportCode()).isEqualTo("JFK");
            assertThat(schedule.getDepartureTime()).isEqualTo(departureTime);
            assertThat(schedule.getArrivalTime()).isEqualTo(arrivalTime);
            assertThat(schedule.getOperatingDays()).containsExactlyInAnyOrderElementsOf(operatingDays);
            assertThat(schedule.getValidFrom()).isEqualTo(validFrom);
            assertThat(schedule.getValidTo()).isEqualTo(validTo);
            assertThat(schedule.getGenerationDaysAhead()).isEqualTo(45);
            assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);
            assertThat(schedule.getScheduleCode()).isNull();
            assertThat(schedule.getLastGeneratedDate()).isNull();
        }

        @Test
        void toEntityDefaultsTheGenerationHorizonToThirtyDays() {
            assertThat(FlightScheduleMapper.toEntity(request(validTo, null)).getGenerationDaysAhead())
                    .isEqualTo(30);
        }

        @Test
        void toEntityKeepsAnOpenEndedScheduleOpenEnded() {
            assertThat(FlightScheduleMapper.toEntity(request(null, null)).getValidTo()).isNull();
        }

        @Test
        void toResponseCarriesEveryFieldIncludingStatusReasonAndAudit() {
            FlightSchedule schedule = FlightScheduleMapper.toEntity(request(validTo, 45));
            schedule.setId(7L);
            schedule.setScheduleCode("SCH-LHR-JFK-000007");
            schedule.setStatus(ScheduleStatus.PAUSED);
            schedule.setStatusReason("Runway Maintenance");
            schedule.setStatusRemarks("Resurfacing 27L");
            schedule.setLastGeneratedDate(LocalDate.of(2026, 10, 1));
            schedule.setCreatedAt(NOW);
            schedule.setUpdatedAt(NOW);
            schedule.setCreatedBy("system");
            schedule.setUpdatedBy("system");
            schedule.setVersion(2L);

            FlightScheduleResponse response = FlightScheduleMapper.toResponse(schedule);

            assertThat(response.id()).isEqualTo(7L);
            assertThat(response.scheduleCode()).isEqualTo("SCH-LHR-JFK-000007");
            assertThat(response.flightNumber()).isEqualTo("BA178");
            assertThat(response.airlineCode()).isEqualTo("BA");
            assertThat(response.originAirportCode()).isEqualTo("LHR");
            assertThat(response.destinationAirportCode()).isEqualTo("JFK");
            assertThat(response.departureTime()).isEqualTo(departureTime);
            assertThat(response.arrivalTime()).isEqualTo(arrivalTime);
            assertThat(response.operatingDays()).containsExactlyInAnyOrderElementsOf(operatingDays);
            assertThat(response.validFrom()).isEqualTo(validFrom);
            assertThat(response.validTo()).isEqualTo(validTo);
            assertThat(response.status()).isEqualTo(ScheduleStatus.PAUSED);
            assertThat(response.lastGeneratedDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(response.generationDaysAhead()).isEqualTo(45);
            assertThat(response.statusReason()).isEqualTo("Runway Maintenance");
            assertThat(response.statusRemarks()).isEqualTo("Resurfacing 27L");
            assertThat(response.createdBy()).isEqualTo("system");
            assertThat(response.updatedBy()).isEqualTo("system");
            assertThat(response.version()).isEqualTo(2L);
            assertThat(response.createdAt()).isEqualTo(NOW);
            assertThat(response.updatedAt()).isEqualTo(NOW);
        }

        @Test
        void toResponseToleratesAnUnsavedUnAuditedSchedule() {
            FlightScheduleResponse response =
                    FlightScheduleMapper.toResponse(FlightScheduleMapper.toEntity(request(null, null)));

            assertThat(response.id()).isNull();
            assertThat(response.scheduleCode()).isNull();
            assertThat(response.validTo()).isNull();
            assertThat(response.lastGeneratedDate()).isNull();
            assertThat(response.statusReason()).isNull();
            assertThat(response.statusRemarks()).isNull();
            assertThat(response.version()).isNull();
            assertThat(response.createdAt()).isNull();
            assertThat(response.updatedAt()).isNull();
            assertThat(response.status()).isEqualTo(ScheduleStatus.ACTIVE);
        }
    }
}
