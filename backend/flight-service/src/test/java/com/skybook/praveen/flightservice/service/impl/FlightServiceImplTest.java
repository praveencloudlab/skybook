package com.skybook.praveen.flightservice.service.impl;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.request.UpdateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.ItineraryResponse;
import com.skybook.praveen.flightservice.dto.response.RouteCalendarDayResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.exception.FlightNotFoundException;
import com.skybook.praveen.flightservice.repository.FlightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every public entry point of the flight service against a mocked repository.
 * The two shopping surfaces get the most attention: the itinerary engine
 * (layover floors/ceilings, same-carrier protection, cancellation and the
 * 60-minute booking cutoff) and the route calendar (day counts, the cutoff
 * again, and the 124-day range cap that keeps one tokenless call from walking
 * a whole year of schedule).
 */
@ExtendWith(MockitoExtension.class)
class FlightServiceImplTest {

    @Mock
    private FlightRepository flightRepository;

    private FlightServiceImpl flightService;

    @BeforeEach
    void setUp() {
        flightService = new FlightServiceImpl(flightRepository, false);
    }

    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 9, 10, 10, 15);
    private static final LocalDateTime ARRIVAL = LocalDateTime.of(2026, 9, 10, 13, 40);

    private Flight flight(Long id, FlightStatus status) {
        return Flight.builder()
                .id(id)
                .flightNumber("BA178")
                .airlineCode("BA")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(DEPARTURE)
                .arrivalTime(ARRIVAL)
                .departureTerminal("5")
                .arrivalTerminal("8")
                .status(status)
                .build();
    }

    private Flight leg(String number, String airline, String origin, String destination,
                       LocalDateTime departure, LocalDateTime arrival) {
        return Flight.builder()
                .id((long) number.hashCode())
                .flightNumber(number)
                .airlineCode(airline)
                .originAirportCode(origin)
                .destinationAirportCode(destination)
                .departureTime(departure)
                .arrivalTime(arrival)
                .status(FlightStatus.SCHEDULED)
                .build();
    }

    private CreateFlightRequest createRequest() {
        return new CreateFlightRequest("ba178", "ba", "lhr", "jfk", DEPARTURE, ARRIVAL);
    }

    // =====================================================
    // CREATE
    // =====================================================

    @Nested
    class Creation {

        @Test
        void createSavesTheMappedEntityAndReturnsIt() {
            when(flightRepository.existsByFlightNumberAndDepartureTime("BA178", DEPARTURE)).thenReturn(false);
            when(flightRepository.save(any(Flight.class))).thenAnswer(inv -> inv.getArgument(0));

            FlightResponse response = flightService.createFlight(createRequest());

            ArgumentCaptor<Flight> captor = ArgumentCaptor.forClass(Flight.class);
            verify(flightRepository).save(captor.capture());
            assertThat(captor.getValue().getFlightNumber()).isEqualTo("BA178");
            assertThat(captor.getValue().getStatus()).isEqualTo(FlightStatus.SCHEDULED);
            assertThat(response.originAirportCode()).isEqualTo("LHR");
            assertThat(response.departureTerminal()).isEqualTo("5");
            assertThat(response.arrivalTerminal()).isEqualTo("8");
        }

        @Test
        void createRejectsTheSameFlightNumberOnTheSameDeparture() {
            when(flightRepository.existsByFlightNumberAndDepartureTime("BA178", DEPARTURE)).thenReturn(true);

            assertThatThrownBy(() -> flightService.createFlight(createRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists for departure time");

            verify(flightRepository, never()).save(any());
        }

        @Test
        void createRejectsArrivalThatIsNotAfterDeparture() {
            when(flightRepository.existsByFlightNumberAndDepartureTime(any(), any())).thenReturn(false);

            assertThatThrownBy(() -> flightService.createFlight(
                    new CreateFlightRequest("BA178", "BA", "LHR", "JFK", DEPARTURE, DEPARTURE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Arrival time must be after departure time");

            verify(flightRepository, never()).save(any());
        }

        @Test
        void createRejectsAFlightThatGoesNowhere() {
            when(flightRepository.existsByFlightNumberAndDepartureTime(any(), any())).thenReturn(false);

            assertThatThrownBy(() -> flightService.createFlight(
                    new CreateFlightRequest("BA178", "BA", "LHR", "lhr", DEPARTURE, ARRIVAL)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Origin and destination airports must be different");

            verify(flightRepository, never()).save(any());
        }

        @Test
        void bulkCreateValidatesEveryRequestAndSavesOneBatch() {
            CreateFlightRequest second = new CreateFlightRequest(
                    "BA179", "BA", "JFK", "LHR", DEPARTURE.plusDays(1), ARRIVAL.plusDays(1));
            when(flightRepository.existsByFlightNumberAndDepartureTime(any(), any())).thenReturn(false);
            when(flightRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<FlightResponse> responses = flightService.createFlights(List.of(createRequest(), second));

            assertThat(responses).hasSize(2);
            assertThat(responses).extracting(FlightResponse::flightNumber)
                    .containsExactly("BA178", "BA179");
            verify(flightRepository).saveAll(anyList());
        }

        @Test
        void bulkCreateSavesNothingWhenAnySingleRequestIsInvalid() {
            CreateFlightRequest broken = new CreateFlightRequest(
                    "BA179", "BA", "JFK", "JFK", DEPARTURE.plusDays(1), ARRIVAL.plusDays(1));
            when(flightRepository.existsByFlightNumberAndDepartureTime(any(), any())).thenReturn(false);

            assertThatThrownBy(() -> flightService.createFlights(List.of(createRequest(), broken)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Origin and destination airports must be different");

            verify(flightRepository, never()).saveAll(anyList());
        }
    }

    // =====================================================
    // READ
    // =====================================================

    @Nested
    class Reads {

        @Test
        void getByIdReturnsTheMappedFlight() {
            when(flightRepository.findById(1L)).thenReturn(Optional.of(flight(1L, FlightStatus.SCHEDULED)));

            assertThat(flightService.getFlightById(1L).id()).isEqualTo(1L);
        }

        @Test
        void getByIdThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.getFlightById(42L))
                    .isInstanceOf(FlightNotFoundException.class)
                    .hasMessageContaining("42");
        }

        @Test
        void getAllMapsEveryRowOfThePage() {
            var pageable = org.springframework.data.domain.PageRequest.of(0, 50);
            when(flightRepository.findAll(pageable)).thenReturn(
                    new org.springframework.data.domain.PageImpl<>(List.of(
                            flight(1L, FlightStatus.SCHEDULED), flight(2L, FlightStatus.DELAYED))));

            assertThat(flightService.getAllFlights(pageable).getContent()).hasSize(2)
                    .extracting(FlightResponse::status)
                    .containsExactly(FlightStatus.SCHEDULED, FlightStatus.DELAYED);
        }

        @Test
        void getByStatusDelegatesToTheRepository() {
            when(flightRepository.findByStatus(FlightStatus.CANCELLED))
                    .thenReturn(List.of(flight(1L, FlightStatus.CANCELLED)));

            assertThat(flightService.getFlightsByStatus(FlightStatus.CANCELLED)).hasSize(1);
            verify(flightRepository).findByStatus(FlightStatus.CANCELLED);
        }

        @Test
        void searchUppercasesTheRouteAndScansTheWholeRequestedDay() {
            LocalDate date = LocalDate.of(2026, 9, 10);
            when(flightRepository.findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
                    eq("LHR"), eq("JFK"), any(), any()))
                    .thenReturn(List.of(flight(1L, FlightStatus.SCHEDULED)));

            assertThat(flightService.searchFlights("lhr", "jfk", date)).hasSize(1);

            ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(flightRepository).findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
                    eq("LHR"), eq("JFK"), start.capture(), end.capture());
            assertThat(start.getValue()).isEqualTo(date.atStartOfDay());
            assertThat(end.getValue()).isEqualTo(date.plusDays(1).atStartOfDay().minusNanos(1));
        }

        @Test
        void adminSearchDoesNotApplyTheBookingCutoffOrDropCancellations() {
            // Back office has to see today's departed and cancelled flights to run them.
            LocalDate date = LocalDate.of(2026, 9, 10);
            when(flightRepository.findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
                    any(), any(), any(), any()))
                    .thenReturn(List.of(flight(1L, FlightStatus.CANCELLED), flight(2L, FlightStatus.DEPARTED)));

            assertThat(flightService.searchFlights("LHR", "JFK", date)).hasSize(2);
        }

        @Test
        void byDepartureDateScansThatSingleDay() {
            LocalDate date = LocalDate.of(2026, 9, 10);
            when(flightRepository.findByDepartureTimeBetween(any(), any()))
                    .thenReturn(List.of(flight(1L, FlightStatus.SCHEDULED)));

            assertThat(flightService.getFlightsByDepartureDate(date)).hasSize(1);

            ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(flightRepository).findByDepartureTimeBetween(start.capture(), end.capture());
            assertThat(start.getValue()).isEqualTo(date.atStartOfDay());
            assertThat(end.getValue()).isEqualTo(date.plusDays(1).atStartOfDay().minusNanos(1));
        }

        @Test
        void byDepartureDateRangeIsInclusiveOfTheLastDay() {
            LocalDate start = LocalDate.of(2026, 9, 10);
            LocalDate end = LocalDate.of(2026, 9, 12);
            when(flightRepository.findByDepartureTimeBetween(any(), any())).thenReturn(List.of());

            assertThat(flightService.getFlightsByDepartureDateRange(start, end)).isEmpty();

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(flightRepository).findByDepartureTimeBetween(from.capture(), to.capture());
            assertThat(from.getValue()).isEqualTo(start.atStartOfDay());
            assertThat(to.getValue()).isEqualTo(end.plusDays(1).atStartOfDay().minusNanos(1));
        }

        @Test
        void existsDelegatesById() {
            when(flightRepository.existsById(1L)).thenReturn(true);

            assertThat(flightService.exists(1L)).isTrue();
        }

        @Test
        void existsByFlightNumberUppercasesFirst() {
            when(flightRepository.existsByFlightNumber("BA178")).thenReturn(true);

            assertThat(flightService.existsByFlightNumber("ba178")).isTrue();
            verify(flightRepository).existsByFlightNumber("BA178");
        }
    }

    // =====================================================
    // UPDATE / DELETE
    // =====================================================

    @Nested
    class Updates {

        private UpdateFlightRequest updateRequest(String origin, String destination,
                                                  LocalDateTime departure, LocalDateTime arrival) {
            return new UpdateFlightRequest("ek", origin, destination, departure, arrival);
        }

        @Test
        void updateAppliesTheNewRouteAndTimes() {
            Flight existing = flight(1L, FlightStatus.SCHEDULED);
            when(flightRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(flightRepository.save(existing)).thenReturn(existing);

            FlightResponse response = flightService.updateFlight(1L,
                    updateRequest("dxb", "sin", DEPARTURE.plusHours(1), ARRIVAL.plusHours(2)));

            assertThat(response.airlineCode()).isEqualTo("EK");
            assertThat(response.originAirportCode()).isEqualTo("DXB");
            assertThat(response.destinationAirportCode()).isEqualTo("SIN");
            assertThat(response.departureTime()).isEqualTo(DEPARTURE.plusHours(1));
            assertThat(response.arrivalTime()).isEqualTo(ARRIVAL.plusHours(2));
        }

        @Test
        void updateThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.updateFlight(42L,
                    updateRequest("DXB", "SIN", DEPARTURE, ARRIVAL)))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void updateRejectsArrivalThatIsNotAfterDeparture() {
            when(flightRepository.findById(1L)).thenReturn(Optional.of(flight(1L, FlightStatus.SCHEDULED)));

            assertThatThrownBy(() -> flightService.updateFlight(1L,
                    updateRequest("DXB", "SIN", DEPARTURE, DEPARTURE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Arrival time must be after departure time");

            verify(flightRepository, never()).save(any());
        }

        @Test
        void updateRejectsSameOriginAndDestination() {
            when(flightRepository.findById(1L)).thenReturn(Optional.of(flight(1L, FlightStatus.SCHEDULED)));

            assertThatThrownBy(() -> flightService.updateFlight(1L,
                    updateRequest("DXB", "dxb", DEPARTURE, ARRIVAL)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Origin and destination airports must be different");

            verify(flightRepository, never()).save(any());
        }

        @Test
        void statusUpdateWritesWhateverStatusWasAsked() {
            Flight existing = flight(1L, FlightStatus.SCHEDULED);
            when(flightRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(flightRepository.save(existing)).thenReturn(existing);

            assertThat(flightService.updateFlightStatus(1L, FlightStatus.DELAYED).status())
                    .isEqualTo(FlightStatus.DELAYED);
            assertThat(existing.getStatus()).isEqualTo(FlightStatus.DELAYED);
        }

        @Test
        void statusUpdateThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.updateFlightStatus(42L, FlightStatus.DELAYED))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void cancelMovesTheFlightToCancelled() {
            Flight existing = flight(1L, FlightStatus.SCHEDULED);
            when(flightRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(flightRepository.save(existing)).thenReturn(existing);

            assertThat(flightService.cancelFlight(1L).status()).isEqualTo(FlightStatus.CANCELLED);
        }

        @Test
        void cancelThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.cancelFlight(42L))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void delayMovesTheTimesAndFlagsTheFlightDelayed() {
            Flight existing = flight(1L, FlightStatus.SCHEDULED);
            when(flightRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(flightRepository.save(existing)).thenReturn(existing);

            FlightResponse response = flightService.delayFlight(
                    1L, DEPARTURE.plusHours(2), ARRIVAL.plusHours(2));

            assertThat(response.status()).isEqualTo(FlightStatus.DELAYED);
            assertThat(response.departureTime()).isEqualTo(DEPARTURE.plusHours(2));
            assertThat(response.arrivalTime()).isEqualTo(ARRIVAL.plusHours(2));
        }

        @Test
        void delayRejectsArrivalThatIsNotAfterDeparture() {
            when(flightRepository.findById(1L)).thenReturn(Optional.of(flight(1L, FlightStatus.SCHEDULED)));

            assertThatThrownBy(() -> flightService.delayFlight(1L, ARRIVAL, DEPARTURE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Arrival time must be after departure time");

            verify(flightRepository, never()).save(any());
        }

        @Test
        void delayThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.delayFlight(42L, DEPARTURE, ARRIVAL))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void rescheduleMovesTheTimesWithoutTouchingTheStatus() {
            Flight existing = flight(1L, FlightStatus.SCHEDULED);
            when(flightRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(flightRepository.save(existing)).thenReturn(existing);

            FlightResponse response = flightService.rescheduleFlight(
                    1L, DEPARTURE.plusDays(1), ARRIVAL.plusDays(1));

            assertThat(response.departureTime()).isEqualTo(DEPARTURE.plusDays(1));
            assertThat(response.arrivalTime()).isEqualTo(ARRIVAL.plusDays(1));
            assertThat(response.status()).isEqualTo(FlightStatus.SCHEDULED);
        }

        @Test
        void rescheduleRejectsArrivalThatIsNotAfterDeparture() {
            when(flightRepository.findById(1L)).thenReturn(Optional.of(flight(1L, FlightStatus.SCHEDULED)));

            assertThatThrownBy(() -> flightService.rescheduleFlight(1L, ARRIVAL, ARRIVAL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Arrival time must be after departure time");

            verify(flightRepository, never()).save(any());
        }

        @Test
        void rescheduleThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.rescheduleFlight(42L, DEPARTURE, ARRIVAL))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void boardDepartAndArriveWalkTheOperationalStatuses() {
            Flight existing = flight(1L, FlightStatus.SCHEDULED);
            when(flightRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(flightRepository.save(existing)).thenReturn(existing);

            assertThat(flightService.boardFlight(1L).status()).isEqualTo(FlightStatus.BOARDING);
            assertThat(flightService.departFlight(1L).status()).isEqualTo(FlightStatus.DEPARTED);
            assertThat(flightService.arriveFlight(1L).status()).isEqualTo(FlightStatus.ARRIVED);
        }

        @Test
        void boardThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.boardFlight(42L))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void departThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.departFlight(42L))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void arriveThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.arriveFlight(42L))
                    .isInstanceOf(FlightNotFoundException.class);
        }

        @Test
        void deleteRemovesTheFlightItLoaded() {
            Flight existing = flight(1L, FlightStatus.SCHEDULED);
            when(flightRepository.findById(1L)).thenReturn(Optional.of(existing));

            flightService.deleteFlight(1L);

            verify(flightRepository).delete(existing);
        }

        @Test
        void deleteThrowsWhenMissing() {
            when(flightRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> flightService.deleteFlight(42L))
                    .isInstanceOf(FlightNotFoundException.class);

            verify(flightRepository, never()).delete(any());
        }

        @Test
        void restoreIsStillWaitingOnSoftDelete() {
            assertThatThrownBy(() -> flightService.restoreFlight(1L))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Soft Delete");
        }
    }

    // =====================================================
    // ITINERARIES
    // =====================================================

    @Nested
    class Itineraries {

        /** Far enough ahead that the 60-minute booking cutoff never bites. */
        private final LocalDate date = LocalDate.now().plusDays(3);

        private LocalDateTime at(int dayOffset, int hour, int minute) {
            return date.plusDays(dayOffset).atTime(hour, minute);
        }

        private static final java.util.Map<String, ZoneId> ZONES = java.util.Map.of(
                "LHR", ZoneId.of("Europe/London"),
                "JFK", ZoneId.of("America/New_York"),
                "DXB", ZoneId.of("Asia/Dubai"),
                "SIN", ZoneId.of("Asia/Singapore"));

        /**
         * How long the journey takes, given that every time in these fixtures is
         * a wall clock at its own airport.
         *
         * <p>Spelled out through named zones instead of a literal number of
         * minutes for two reasons. The offsets shift - London and New York do
         * not change over on the same dates, so any literal is wrong for a
         * fortnight each spring and autumn. And the naive answer is available
         * for comparison: a direct LHR-JFK here subtracts to 8h and actually
         * takes 13h, so a test written against the literal would go green again
         * the moment the zone handling was removed.
         */
        private static long journeyMinutes(String origin, LocalDateTime departure,
                                           String destination, LocalDateTime arrival) {
            return java.time.Duration.between(
                    departure.atZone(ZONES.get(origin)),
                    arrival.atZone(ZONES.get(destination))).toMinutes();
        }

        private void windowContains(List<Flight> flights) {
            when(flightRepository.findByDepartureTimeBetween(any(), any())).thenReturn(flights);
        }

        @Test
        void directFlightsHaveNoStopsNoLayoversAndAreTriviallySameCarrier() {
            windowContains(List.of(leg("BA178", "BA", "LHR", "JFK", at(0, 9, 0), at(0, 17, 0))));

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(1);
            ItineraryResponse direct = itineraries.get(0);
            assertThat(direct.stops()).isZero();
            assertThat(direct.legs()).hasSize(1);
            assertThat(direct.layoverMinutes()).isEmpty();
            assertThat(direct.totalDurationMinutes())
                    .isEqualTo(journeyMinutes("LHR", at(0, 9, 0), "JFK", at(0, 17, 0)));
            // 09:00 in London to 17:00 in New York subtracts to eight hours and
            // is thirteen. Stated explicitly so the naive answer cannot pass.
            assertThat(direct.totalDurationMinutes()).isNotEqualTo(480);
            assertThat(direct.sameCarrier()).isTrue();
        }

        @Test
        void theRouteCodesAreUppercasedBeforeMatching() {
            windowContains(List.of(leg("BA178", "BA", "LHR", "JFK", at(0, 9, 0), at(0, 17, 0))));

            assertThat(flightService.getItineraries("lhr", "jfk", date)).hasSize(1);
        }

        @Test
        void oneStopReportsTheLayoverAndTheThroughTicketFlag() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 8, 0), at(0, 16, 0)),
                    leg("EK2", "EK", "DXB", "JFK", at(0, 17, 0), at(1, 3, 0))));

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(1);
            ItineraryResponse oneStop = itineraries.get(0);
            assertThat(oneStop.stops()).isEqualTo(1);
            assertThat(oneStop.legs()).extracting(FlightResponse::flightNumber)
                    .containsExactly("EK1", "EK2");
            // The layover is the one span that DOES subtract directly: both
            // sides of a connection are the same airport, so one clock.
            assertThat(oneStop.layoverMinutes()).containsExactly(60L);
            assertThat(oneStop.totalDurationMinutes())
                    .isEqualTo(journeyMinutes("LHR", at(0, 8, 0), "JFK", at(1, 3, 0)));
            assertThat(oneStop.sameCarrier()).isTrue();
        }

        @Test
        void mixedCarriersAreSoldAsASelfTransferNotAThroughTicket() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 8, 0), at(0, 16, 0)),
                    leg("QR2", "QR", "DXB", "JFK", at(0, 17, 0), at(1, 3, 0))));

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(1);
            assertThat(itineraries.get(0).sameCarrier()).isFalse();
        }

        @Test
        void aProtectedSameCarrierJunctionNeedsOnly45MinutesOfSlack() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 8, 0), at(0, 16, 0)),
                    leg("EK2", "EK", "DXB", "JFK", at(0, 16, 45), at(1, 3, 0))));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).hasSize(1);
        }

        @Test
        void belowTheSameCarrierFloorTheConnectionIsNotOffered() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 8, 0), at(0, 16, 0)),
                    leg("EK2", "EK", "DXB", "JFK", at(0, 16, 44), at(1, 3, 0))));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).isEmpty();
        }

        @Test
        void aSelfTransferNeedsAFull60MinutesToCollectAndReCheckBags() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 8, 0), at(0, 16, 0)),
                    leg("QR2", "QR", "DXB", "JFK", at(0, 16, 59), at(1, 3, 0))));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).isEmpty();
        }

        @Test
        void aWaitBeyondSevenHoursIsNoLongerAConnection() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 8, 0), at(0, 16, 0)),
                    leg("EK2", "EK", "DXB", "JFK", at(0, 23, 1), at(1, 9, 0))));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).isEmpty();

            // Exactly seven hours still connects.
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 8, 0), at(0, 16, 0)),
                    leg("EK2", "EK", "DXB", "JFK", at(0, 23, 0), at(1, 9, 0))));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).hasSize(1);
        }

        @Test
        void twoStopsChainBothLayoversInLegOrder() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 6, 0), at(0, 12, 0)),
                    leg("EK2", "EK", "DXB", "SIN", at(0, 13, 0), at(0, 20, 0)),
                    leg("SQ3", "SQ", "SIN", "JFK", at(0, 22, 0), at(1, 12, 0))));

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(1);
            ItineraryResponse twoStop = itineraries.get(0);
            assertThat(twoStop.stops()).isEqualTo(2);
            assertThat(twoStop.layoverMinutes()).containsExactly(60L, 120L);
            assertThat(twoStop.legs()).extracting(FlightResponse::flightNumber)
                    .containsExactly("EK1", "EK2", "SQ3");
            assertThat(twoStop.sameCarrier()).isFalse();
            assertThat(twoStop.totalDurationMinutes())
                    .isEqualTo(journeyMinutes("LHR", at(0, 6, 0), "JFK", at(1, 12, 0)));
        }

        @Test
        void theSecondLayoverIsPolicedTheSameWayAsTheFirst() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 6, 0), at(0, 12, 0)),
                    leg("EK2", "EK", "DXB", "SIN", at(0, 13, 0), at(0, 20, 0)),
                    leg("SQ3", "SQ", "SIN", "JFK", at(0, 20, 30), at(1, 12, 0))));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).isEmpty();
        }

        @Test
        void aThirdLegToTheWrongCityOrOutsideTheLayoverBandIsPassedOver() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 6, 0), at(0, 12, 0)),
                    leg("EK2", "EK", "DXB", "SIN", at(0, 13, 0), at(0, 20, 0)),
                    // Right junction, wrong city.
                    leg("SQ9", "SQ", "SIN", "SYD", at(0, 21, 30), at(1, 8, 0)),
                    // Right city, half an hour to change carriers - not enough.
                    leg("SQ3", "SQ", "SIN", "JFK", at(0, 20, 30), at(1, 12, 0)),
                    // Right city, but an eight-hour sit is not a connection.
                    leg("SQ5", "SQ", "SIN", "JFK", at(1, 4, 0), at(1, 18, 0)),
                    // Right city, 90 minutes - bookable.
                    leg("SQ4", "SQ", "SIN", "JFK", at(0, 21, 30), at(1, 12, 0))));

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(1);
            assertThat(itineraries.get(0).legs()).extracting(FlightResponse::flightNumber)
                    .containsExactly("EK1", "EK2", "SQ4");
        }

        @Test
        void aConnectionBackToTheOriginIsNeverExtendedIntoATwoStop() {
            windowContains(List.of(
                    leg("EK1", "EK", "LHR", "DXB", at(0, 6, 0), at(0, 12, 0)),
                    leg("EK2", "EK", "DXB", "LHR", at(0, 13, 0), at(0, 20, 0)),
                    leg("BA9", "BA", "LHR", "JFK", at(0, 21, 0), at(1, 5, 0))));

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(1);
            assertThat(itineraries.get(0).stops()).isZero();
        }

        @Test
        void cancelledLegsAreNeverOfferedForSale() {
            Flight cancelled = leg("BA178", "BA", "LHR", "JFK", at(0, 9, 0), at(0, 17, 0));
            cancelled.setStatus(FlightStatus.CANCELLED);
            windowContains(List.of(cancelled));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).isEmpty();
        }

        @Test
        void aLegInsideTheSixtyMinuteBookingCutoffIsNotBookable() {
            LocalDateTime soon = LocalDateTime.now().plusMinutes(30);
            Flight leaving = leg("BA178", "BA", "LHR", "JFK", soon, soon.plusHours(8));
            windowContains(List.of(leaving));

            assertThat(flightService.getItineraries("LHR", "JFK", soon.toLocalDate())).isEmpty();
        }

        @Test
        void onwardLegsMayRunIntoTheNextDayButFirstLegsMayNot() {
            // The repository window spans two days on purpose; only a leg that
            // departs on the requested date can start an itinerary.
            windowContains(List.of(
                    leg("BA178", "BA", "LHR", "JFK", at(0, 9, 0), at(0, 17, 0)),
                    leg("BA179", "BA", "LHR", "JFK", at(1, 9, 0), at(1, 17, 0))));

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(1);
            assertThat(itineraries.get(0).legs().get(0).flightNumber()).isEqualTo("BA178");
        }

        @Test
        void anItineraryLongerThanFortyHoursIsDropped() {
            windowContains(List.of(
                    leg("BA178", "BA", "LHR", "JFK", at(0, 0, 0), at(1, 17, 0))));

            assertThat(flightService.getItineraries("LHR", "JFK", date)).isEmpty();
        }

        @Test
        void resultsAreSortedByTotalDurationAndCappedAtTwenty() {
            List<Flight> window = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                window.add(leg("BA" + i, "BA", "LHR", "JFK", at(0, 6, 0), at(0, 14, 0).plusMinutes(i)));
            }
            windowContains(window);

            List<ItineraryResponse> itineraries = flightService.getItineraries("LHR", "JFK", date);

            assertThat(itineraries).hasSize(20);
            assertThat(itineraries).extracting(ItineraryResponse::totalDurationMinutes)
                    .isSorted();
            assertThat(itineraries.get(0).totalDurationMinutes())
                    .isEqualTo(journeyMinutes("LHR", at(0, 6, 0), "JFK", at(0, 14, 0)));
        }

        @Test
        void anUnservedRouteReturnsNothingRatherThanFailing() {
            windowContains(List.of(leg("BA178", "BA", "LHR", "JFK", at(0, 9, 0), at(0, 17, 0))));

            assertThat(flightService.getItineraries("SYD", "NBO", date)).isEmpty();
        }

        @Test
        void theWindowCoversTheRequestedDayPlusTwoForOvernightConnections() {
            windowContains(List.of());

            flightService.getItineraries("LHR", "JFK", date);

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(flightRepository).findByDepartureTimeBetween(from.capture(), to.capture());
            assertThat(from.getValue()).isEqualTo(date.atStartOfDay());
            assertThat(to.getValue()).isEqualTo(date.plusDays(2).atStartOfDay());
        }
    }

    // =====================================================
    // ROUTE CALENDAR
    // =====================================================

    @Nested
    class RouteCalendar {

        private final LocalDate start = LocalDate.now().plusDays(2);

        /** Departures out of the origin - direct legs and possible first legs. */
        private void outboundContains(List<Flight> flights) {
            when(flightRepository.findByOriginAirportCodeAndDepartureTimeBetween(
                    any(), any(), any())).thenReturn(flights);
        }

        /** Arrivals into the destination - the possible onward legs. */
        private void onwardContains(List<Flight> flights) {
            when(flightRepository.findByDestinationAirportCodeAndDepartureTimeBetween(
                    any(), any(), any())).thenReturn(flights);
        }

        private void routeContains(List<Flight> flights) {
            outboundContains(flights);
            onwardContains(List.of());
        }

        @Test
        void countsBookableDeparturesPerDayInDateOrder() {
            routeContains(List.of(
                    leg("BA1", "BA", "LHR", "JFK", start.atTime(9, 0), start.atTime(17, 0)),
                    leg("BA2", "BA", "LHR", "JFK", start.atTime(19, 0), start.plusDays(1).atTime(3, 0)),
                    leg("BA3", "BA", "LHR", "JFK", start.plusDays(2).atTime(9, 0),
                            start.plusDays(2).atTime(17, 0))));

            List<RouteCalendarDayResponse> calendar =
                    flightService.getRouteCalendar("lhr", "jfk", start, start.plusDays(3));

            assertThat(calendar).hasSize(2);
            assertThat(calendar.get(0).date()).isEqualTo(start);
            assertThat(calendar.get(0).flights()).isEqualTo(2);
            assertThat(calendar.get(1).date()).isEqualTo(start.plusDays(2));
            assertThat(calendar.get(1).flights()).isEqualTo(1);
        }

        @Test
        void cancelledDeparturesDoNotCountTowardsADay() {
            Flight cancelled = leg("BA2", "BA", "LHR", "JFK", start.atTime(19, 0), start.atTime(23, 0));
            cancelled.setStatus(FlightStatus.CANCELLED);
            routeContains(List.of(
                    leg("BA1", "BA", "LHR", "JFK", start.atTime(9, 0), start.atTime(17, 0)),
                    cancelled));

            List<RouteCalendarDayResponse> calendar =
                    flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(3));

            assertThat(calendar).singleElement()
                    .satisfies(day -> assertThat(day.flights()).isEqualTo(1));
        }

        @Test
        void todaysAlreadyGoneDeparturesAreNotPricedAsAvailable() {
            LocalDateTime soon = LocalDateTime.now().plusMinutes(30);
            routeContains(List.of(leg("BA1", "BA", "LHR", "JFK", soon, soon.plusHours(8))));

            assertThat(flightService.getRouteCalendar(
                    "LHR", "JFK", soon.toLocalDate(), soon.toLocalDate().plusDays(1))).isEmpty();
        }

        @Test
        void daysWithoutFlightsAreSimplyAbsent() {
            routeContains(List.of());

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(10))).isEmpty();
        }

        @Test
        void aBackwardsRangeIsRejected() {
            assertThatThrownBy(() -> flightService.getRouteCalendar(
                    "LHR", "JFK", start, start.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("endDate must not be before startDate");

            verify(flightRepository, never())
                    .findByOriginAirportCodeAndDepartureTimeBetween(any(), any(), any());
        }

        // ---- connection days (the honest networks are route maps, not
        // meshes - most tier-2 pairs are one-stop journeys via a hub, and
        // the picker must light the days a search would satisfy) ----------

        @Test
        void aConnectionOnlyRouteLightsTheDayItsHubJourneyWorks() {
            // IXE-CCU has no nonstop; IXE-BLR + BLR-CCU with a 73-minute
            // same-carrier layover is exactly what /itineraries offers.
            outboundContains(List.of(
                    leg("IX1224", "IX", "IXE", "BLR", start.atTime(9, 12), start.atTime(10, 17))));
            onwardContains(List.of(
                    leg("IX771", "IX", "BLR", "CCU", start.atTime(11, 30), start.atTime(14, 5))));

            List<RouteCalendarDayResponse> calendar =
                    flightService.getRouteCalendar("IXE", "CCU", start, start.plusDays(3));

            assertThat(calendar).singleElement().satisfies(day -> {
                assertThat(day.date()).isEqualTo(start);
                assertThat(day.flights()).isEqualTo(1);
            });
        }

        @Test
        void directAndConnectionOptionsSumOnTheSameDay() {
            outboundContains(List.of(
                    leg("BA1", "BA", "LHR", "JFK", start.atTime(9, 0), start.atTime(17, 0)),
                    leg("BA7", "BA", "LHR", "DXB", start.atTime(8, 0), start.atTime(16, 0))));
            onwardContains(List.of(
                    leg("EK201", "EK", "DXB", "JFK", start.atTime(18, 0), start.plusDays(1).atTime(2, 0))));

            List<RouteCalendarDayResponse> calendar =
                    flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(1));

            assertThat(calendar).singleElement()
                    .satisfies(day -> assertThat(day.flights()).isEqualTo(2));
        }

        @Test
        void aLayoverTooShortForASelfTransferDoesNotCount() {
            // 50 minutes: enough for a protected same-carrier junction,
            // not for collecting and re-checking bags across carriers.
            outboundContains(List.of(
                    leg("BA7", "BA", "LHR", "DXB", start.atTime(8, 0), start.atTime(16, 0))));
            onwardContains(List.of(
                    leg("EK201", "EK", "DXB", "JFK", start.atTime(16, 50), start.plusDays(1).atTime(1, 0))));

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(1)))
                    .isEmpty();
        }

        @Test
        void theSameCarrierJunctionIsProtectedAtFortyFiveMinutes() {
            outboundContains(List.of(
                    leg("EK2", "EK", "LHR", "DXB", start.atTime(8, 0), start.atTime(16, 0))));
            onwardContains(List.of(
                    leg("EK201", "EK", "DXB", "JFK", start.atTime(16, 50), start.plusDays(1).atTime(1, 0))));

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(1)))
                    .singleElement()
                    .satisfies(day -> assertThat(day.flights()).isEqualTo(1));
        }

        @Test
        void beyondSevenHoursNobodyCallsItAConnection() {
            outboundContains(List.of(
                    leg("BA7", "BA", "LHR", "DXB", start.atTime(6, 0), start.atTime(14, 0))));
            onwardContains(List.of(
                    leg("EK201", "EK", "DXB", "JFK", start.atTime(21, 30), start.plusDays(1).atTime(6, 0))));

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(1)))
                    .isEmpty();
        }

        @Test
        void anOvernightFirstLegConnectsTheNextMorning() {
            outboundContains(List.of(
                    leg("BA7", "BA", "LHR", "DXB", start.atTime(22, 0), start.plusDays(1).atTime(6, 0))));
            onwardContains(List.of(
                    leg("EK201", "EK", "DXB", "JFK", start.plusDays(1).atTime(8, 0),
                            start.plusDays(1).atTime(16, 0))));

            List<RouteCalendarDayResponse> calendar =
                    flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(1));

            // The option lights the day the JOURNEY departs, not the day it connects.
            assertThat(calendar).singleElement()
                    .satisfies(day -> assertThat(day.date()).isEqualTo(start));
        }

        @Test
        void aLegBackThroughTheOriginIsNotAConnection() {
            // LHR-JFK "via" a leg that departs LHR itself is the direct in
            // disguise; the onward filter must exclude the origin as a hub.
            outboundContains(List.of(
                    leg("BA7", "BA", "LHR", "DXB", start.atTime(8, 0), start.atTime(16, 0))));
            onwardContains(List.of(
                    leg("BA1", "BA", "LHR", "JFK", start.atTime(18, 0), start.plusDays(1).atTime(2, 0))));

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(1)))
                    .isEmpty();
        }

        @Test
        void aCancelledOnwardLegDoesNotMakeADayBookable() {
            Flight cancelled = leg("EK201", "EK", "DXB", "JFK",
                    start.atTime(18, 0), start.plusDays(1).atTime(2, 0));
            cancelled.setStatus(FlightStatus.CANCELLED);
            outboundContains(List.of(
                    leg("BA7", "BA", "LHR", "DXB", start.atTime(8, 0), start.atTime(16, 0))));
            onwardContains(List.of(cancelled));

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(1)))
                    .isEmpty();
        }

        @Test
        void aSingleDayRangeIsFine() {
            routeContains(List.of());

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start)).isEmpty();
        }

        @Test
        void the124DayCapIsTheLastAcceptedRange() {
            routeContains(List.of());

            assertThat(flightService.getRouteCalendar("LHR", "JFK", start, start.plusDays(124))).isEmpty();
        }

        @Test
        void oneDayBeyondTheCapIsRejected() {
            assertThatThrownBy(() -> flightService.getRouteCalendar(
                    "LHR", "JFK", start, start.plusDays(125)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Calendar range must not exceed 124 days");

            verify(flightRepository, never())
                    .findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
                            any(), any(), any(), any());
        }

        @Test
        void theQueryWindowCoversTheWholeOfTheLastRequestedDay() {
            routeContains(List.of());

            flightService.getRouteCalendar("lhr", "jfk", start, start.plusDays(5));

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(flightRepository).findByOriginAirportCodeAndDepartureTimeBetween(
                    eq("LHR"), from.capture(), to.capture());
            assertThat(from.getValue()).isEqualTo(start.atStartOfDay());
            assertThat(to.getValue()).isEqualTo(start.plusDays(6).atStartOfDay().minusNanos(1));

            // The onward window runs a day longer: an overnight first leg
            // hands over to an onward departing the following day.
            ArgumentCaptor<LocalDateTime> onwardTo = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(flightRepository).findByDestinationAirportCodeAndDepartureTimeBetween(
                    eq("JFK"), any(), onwardTo.capture());
            assertThat(onwardTo.getValue()).isEqualTo(start.plusDays(7).atStartOfDay());
        }
    }
}
