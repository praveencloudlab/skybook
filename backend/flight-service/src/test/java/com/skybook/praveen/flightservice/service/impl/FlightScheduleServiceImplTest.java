package com.skybook.praveen.flightservice.service.impl;

import com.skybook.praveen.flightservice.dto.request.CreateFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.FlightScheduleResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.entity.FlightSchedule;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import com.skybook.praveen.flightservice.exception.FlightScheduleNotFoundException;
import com.skybook.praveen.flightservice.repository.FlightRepository;
import com.skybook.praveen.flightservice.repository.FlightScheduleRepository;
import com.skybook.praveen.flightservice.service.FlightScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schedule lifecycle plus the generation engine. Generation is the part with
 * real teeth: the window is computed from the lastGeneratedDate high-water mark
 * (never re-walking the past), operating days are the only days that produce a
 * flight, an instance that already exists is skipped so a re-run is a no-op,
 * and reaching validTo retires the schedule.
 */
@ExtendWith(MockitoExtension.class)
class FlightScheduleServiceImplTest {

    @Mock
    private FlightScheduleRepository flightScheduleRepository;

    @Mock
    private FlightRepository flightRepository;

    /** The proxied self-reference the nightly sweep routes generation through. */
    @Mock
    private FlightScheduleService self;

    private FlightScheduleServiceImpl scheduleService;

    private static final LocalTime DEPARTS = LocalTime.of(10, 15);
    private static final LocalTime ARRIVES = LocalTime.of(13, 40);
    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        scheduleService = new FlightScheduleServiceImpl(flightScheduleRepository, flightRepository, self);
    }

    private CreateFlightScheduleRequest createRequest(String origin, String destination,
                                                      LocalDate validFrom, LocalDate validTo,
                                                      Integer horizon) {
        return new CreateFlightScheduleRequest(
                "ba178", "ba", origin, destination, DEPARTS, ARRIVES,
                EnumSet.of(DayOfWeek.MONDAY), validFrom, validTo, horizon);
    }

    private FlightSchedule schedule(Long id, ScheduleStatus status) {
        FlightSchedule schedule = FlightSchedule.builder()
                .id(id)
                .scheduleCode("SCH-LHR-JFK-%06d".formatted(id))
                .flightNumber("BA178")
                .airlineCode("BA")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(DEPARTS)
                .arrivalTime(ARRIVES)
                .operatingDays(EnumSet.allOf(DayOfWeek.class))
                .validFrom(TODAY)
                .status(status)
                .generationDaysAhead(6)
                .build();
        return schedule;
    }

    private Flight generatedFlight(Long id, FlightStatus status) {
        return Flight.builder()
                .id(id)
                .flightNumber("BA178")
                .airlineCode("BA")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(TODAY.plusDays(3).atTime(DEPARTS))
                .arrivalTime(TODAY.plusDays(3).atTime(ARRIVES))
                .status(status)
                .build();
    }

    // =====================================================
    // CREATE
    // =====================================================

    @Nested
    class Creation {

        @Test
        void scheduleCodeIsDerivedFromTheGeneratedIdAfterTheFirstSave() {
            when(flightScheduleRepository.save(any(FlightSchedule.class))).thenAnswer(invocation -> {
                FlightSchedule saved = invocation.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId(7L);
                }
                return saved;
            });

            FlightScheduleResponse response = scheduleService.createSchedule(
                    createRequest("lhr", "jfk", TODAY, TODAY.plusMonths(3), 45));

            assertThat(response.id()).isEqualTo(7L);
            assertThat(response.scheduleCode()).isEqualTo("SCH-LHR-JFK-000007");
            assertThat(response.status()).isEqualTo(ScheduleStatus.ACTIVE);
            assertThat(response.generationDaysAhead()).isEqualTo(45);
            // Once to obtain the id, once to persist the derived code.
            verify(flightScheduleRepository, org.mockito.Mockito.times(2)).save(any(FlightSchedule.class));
        }

        @Test
        void anOpenEndedScheduleIsAccepted() {
            when(flightScheduleRepository.save(any(FlightSchedule.class))).thenAnswer(invocation -> {
                FlightSchedule saved = invocation.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId(1L);
                }
                return saved;
            });

            assertThat(scheduleService.createSchedule(
                    createRequest("LHR", "JFK", TODAY, null, null)).validTo()).isNull();
        }

        @Test
        void aScheduleThatGoesNowhereIsRejected() {
            assertThatThrownBy(() -> scheduleService.createSchedule(
                    createRequest("LHR", "lhr", TODAY, TODAY.plusMonths(3), null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Origin and destination airports must be different");

            verify(flightScheduleRepository, never()).save(any());
        }

        @Test
        void validToMustBeStrictlyAfterValidFrom() {
            assertThatThrownBy(() -> scheduleService.createSchedule(
                    createRequest("LHR", "JFK", TODAY, TODAY, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("validTo must be after validFrom");

            assertThatThrownBy(() -> scheduleService.createSchedule(
                    createRequest("LHR", "JFK", TODAY, TODAY.minusDays(1), null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("validTo must be after validFrom");

            verify(flightScheduleRepository, never()).save(any());
        }
    }

    // =====================================================
    // READ
    // =====================================================

    @Nested
    class Reads {

        @Test
        void getByIdReturnsTheMappedSchedule() {
            when(flightScheduleRepository.findById(7L))
                    .thenReturn(Optional.of(schedule(7L, ScheduleStatus.ACTIVE)));

            assertThat(scheduleService.getScheduleById(7L).scheduleCode())
                    .isEqualTo("SCH-LHR-JFK-000007");
        }

        @Test
        void getByIdThrowsWhenMissing() {
            when(flightScheduleRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.getScheduleById(42L))
                    .isInstanceOf(FlightScheduleNotFoundException.class)
                    .hasMessageContaining("42");
        }

        @Test
        void getAllMapsEveryRow() {
            when(flightScheduleRepository.findAll()).thenReturn(List.of(
                    schedule(1L, ScheduleStatus.ACTIVE), schedule(2L, ScheduleStatus.PAUSED)));

            assertThat(scheduleService.getAllSchedules()).hasSize(2)
                    .extracting(FlightScheduleResponse::status)
                    .containsExactly(ScheduleStatus.ACTIVE, ScheduleStatus.PAUSED);
        }
    }

    // =====================================================
    // PAUSE / RESUME / CANCEL / EXTEND
    // =====================================================

    @Nested
    class Lifecycle {

        @Test
        void pauseStopsGenerationAndRecordsWhy() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightScheduleRepository.save(active)).thenReturn(active);

            FlightScheduleResponse response =
                    scheduleService.pauseSchedule(7L, "Runway Maintenance", "27L resurfacing");

            assertThat(response.status()).isEqualTo(ScheduleStatus.PAUSED);
            assertThat(response.statusReason()).isEqualTo("Runway Maintenance");
            assertThat(response.statusRemarks()).isEqualTo("27L resurfacing");
        }

        @Test
        void pauseWithoutAReasonIsStillAllowed() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightScheduleRepository.save(active)).thenReturn(active);

            assertThat(scheduleService.pauseSchedule(7L, null, null).statusReason()).isNull();
        }

        @Test
        void aCancelledScheduleCannotBePaused() {
            when(flightScheduleRepository.findById(7L))
                    .thenReturn(Optional.of(schedule(7L, ScheduleStatus.CANCELLED)));

            assertThatThrownBy(() -> scheduleService.pauseSchedule(7L, "Weather", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cancelled schedules cannot be paused");

            verify(flightScheduleRepository, never()).save(any());
        }

        @Test
        void pauseThrowsWhenMissing() {
            when(flightScheduleRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.pauseSchedule(42L, null, null))
                    .isInstanceOf(FlightScheduleNotFoundException.class);
        }

        @Test
        void resumeReactivatesAndClearsThePauseReason() {
            FlightSchedule paused = schedule(7L, ScheduleStatus.PAUSED);
            paused.setStatusReason("Runway Maintenance");
            paused.setStatusRemarks("27L resurfacing");
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(paused));
            when(flightScheduleRepository.save(paused)).thenReturn(paused);

            FlightScheduleResponse response = scheduleService.resumeSchedule(7L);

            assertThat(response.status()).isEqualTo(ScheduleStatus.ACTIVE);
            assertThat(response.statusReason()).isNull();
            assertThat(response.statusRemarks()).isNull();
        }

        @Test
        void onlyPausedSchedulesCanBeResumed() {
            when(flightScheduleRepository.findById(7L))
                    .thenReturn(Optional.of(schedule(7L, ScheduleStatus.ACTIVE)));

            assertThatThrownBy(() -> scheduleService.resumeSchedule(7L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Only paused schedules can be resumed");

            verify(flightScheduleRepository, never()).save(any());
        }

        @Test
        void resumeThrowsWhenMissing() {
            when(flightScheduleRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.resumeSchedule(42L))
                    .isInstanceOf(FlightScheduleNotFoundException.class);
        }

        @Test
        void cancelTakesDownEveryStillBookableGeneratedFlight() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            Flight scheduled = generatedFlight(1L, FlightStatus.SCHEDULED);
            Flight delayed = generatedFlight(2L, FlightStatus.DELAYED);
            Flight alreadyGone = generatedFlight(3L, FlightStatus.DEPARTED);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightScheduleRepository.save(active)).thenReturn(active);
            when(flightRepository.findBySchedule_IdAndDepartureTimeAfter(eq(7L), any()))
                    .thenReturn(List.of(scheduled, delayed, alreadyGone));

            FlightScheduleResponse response =
                    scheduleService.cancelSchedule(7L, "Route Withdrawn", "Loss making");

            assertThat(response.status()).isEqualTo(ScheduleStatus.CANCELLED);
            assertThat(response.statusReason()).isEqualTo("Route Withdrawn");
            assertThat(response.statusRemarks()).isEqualTo("Loss making");
            assertThat(scheduled.getStatus()).isEqualTo(FlightStatus.CANCELLED);
            assertThat(delayed.getStatus()).isEqualTo(FlightStatus.CANCELLED);
            // A flight that already left is history - it is not rewritten.
            assertThat(alreadyGone.getStatus()).isEqualTo(FlightStatus.DEPARTED);
            verify(flightRepository).saveAll(List.of(scheduled, delayed, alreadyGone));
        }

        @Test
        void cancellingAScheduleWithNoFutureFlightsIsStillFine() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightScheduleRepository.save(active)).thenReturn(active);
            when(flightRepository.findBySchedule_IdAndDepartureTimeAfter(eq(7L), any()))
                    .thenReturn(List.of());

            assertThat(scheduleService.cancelSchedule(7L, null, null).status())
                    .isEqualTo(ScheduleStatus.CANCELLED);
            verify(flightRepository).saveAll(List.of());
        }

        @Test
        void cancelThrowsWhenMissing() {
            when(flightScheduleRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.cancelSchedule(42L, null, null))
                    .isInstanceOf(FlightScheduleNotFoundException.class);
        }

        @Test
        void extendPushesValidToFurtherOut() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setValidTo(TODAY.plusDays(30));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightScheduleRepository.save(active)).thenReturn(active);

            FlightScheduleResponse response = scheduleService.extendSchedule(7L, TODAY.plusDays(60));

            assertThat(response.validTo()).isEqualTo(TODAY.plusDays(60));
            assertThat(response.status()).isEqualTo(ScheduleStatus.ACTIVE);
        }

        @Test
        void extendingACompletedScheduleRevivesIt() {
            FlightSchedule completed = schedule(7L, ScheduleStatus.COMPLETED);
            completed.setValidTo(TODAY.minusDays(1));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(completed));
            when(flightScheduleRepository.save(completed)).thenReturn(completed);

            FlightScheduleResponse response = scheduleService.extendSchedule(7L, TODAY.plusDays(30));

            assertThat(response.status()).isEqualTo(ScheduleStatus.ACTIVE);
            assertThat(response.validTo()).isEqualTo(TODAY.plusDays(30));
        }

        @Test
        void anOpenEndedScheduleCanBeGivenAnEndDate() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightScheduleRepository.save(active)).thenReturn(active);

            assertThat(scheduleService.extendSchedule(7L, TODAY.plusDays(10)).validTo())
                    .isEqualTo(TODAY.plusDays(10));
        }

        @Test
        void aCancelledScheduleCannotBeExtended() {
            when(flightScheduleRepository.findById(7L))
                    .thenReturn(Optional.of(schedule(7L, ScheduleStatus.CANCELLED)));

            assertThatThrownBy(() -> scheduleService.extendSchedule(7L, TODAY.plusDays(30)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cancelled schedules cannot be extended");

            verify(flightScheduleRepository, never()).save(any());
        }

        @Test
        void extendMustActuallyMoveTheEndDateForward() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setValidTo(TODAY.plusDays(30));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));

            assertThatThrownBy(() -> scheduleService.extendSchedule(7L, TODAY.plusDays(30)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("New validTo must be after the current validTo");

            assertThatThrownBy(() -> scheduleService.extendSchedule(7L, TODAY.plusDays(29)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("New validTo must be after the current validTo");

            verify(flightScheduleRepository, never()).save(any());
        }

        @Test
        void extendThrowsWhenMissing() {
            when(flightScheduleRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.extendSchedule(42L, TODAY.plusDays(30)))
                    .isInstanceOf(FlightScheduleNotFoundException.class);
        }
    }

    // =====================================================
    // GENERATION
    // =====================================================

    @Nested
    class Generation {

        @SuppressWarnings("unchecked")
        private List<Flight> capturedGeneratedFlights() {
            ArgumentCaptor<List<Flight>> captor = ArgumentCaptor.forClass(List.class);
            verify(flightRepository).saveAll(captor.capture());
            return captor.getValue();
        }

        private void nothingGeneratedYet() {
            when(flightRepository.existsByFlightNumberAndDepartureTime(any(), any())).thenReturn(false);
        }

        @Test
        void aFullWeekWindowGeneratesOneFlightPerOperatingDayInclusiveOfBothEnds() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            List<FlightResponse> generated = scheduleService.generateFlights(7L, null);

            // generationDaysAhead = 6, window is inclusive at both ends -> 7 days.
            assertThat(generated).hasSize(7);
            assertThat(capturedGeneratedFlights()).hasSize(7);
            assertThat(generated.get(0).departureTime()).isEqualTo(TODAY.atTime(DEPARTS));
            assertThat(generated.get(0).arrivalTime()).isEqualTo(TODAY.atTime(ARRIVES));
            assertThat(generated.get(0).flightNumber()).isEqualTo("BA178");
            assertThat(generated.get(0).status()).isEqualTo(FlightStatus.SCHEDULED);
            assertThat(generated.get(6).departureTime()).isEqualTo(TODAY.plusDays(6).atTime(DEPARTS));
        }

        @Test
        void onlyOperatingDaysProduceFlights() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setOperatingDays(Set.of(TODAY.getDayOfWeek()));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            List<FlightResponse> generated = scheduleService.generateFlights(7L, null);

            assertThat(generated).hasSize(1);
            assertThat(generated.get(0).departureTime().toLocalDate()).isEqualTo(TODAY);
        }

        @Test
        void everyGeneratedFlightIsLinkedBackToItsSchedule() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setOperatingDays(Set.of(TODAY.getDayOfWeek()));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            scheduleService.generateFlights(7L, null);

            assertThat(capturedGeneratedFlights()).singleElement()
                    .satisfies(flight -> {
                        assertThat(flight.getSchedule()).isSameAs(active);
                        assertThat(flight.getOriginAirportCode()).isEqualTo("LHR");
                        assertThat(flight.getDestinationAirportCode()).isEqualTo("JFK");
                        assertThat(flight.getAirlineCode()).isEqualTo("BA");
                    });
        }

        @Test
        void anArrivalEarlierInTheDayThanDepartureLandsTheNextMorning() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setOperatingDays(Set.of(TODAY.getDayOfWeek()));
            active.setDepartureTime(LocalTime.of(22, 30));
            active.setArrivalTime(LocalTime.of(6, 15));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            List<FlightResponse> generated = scheduleService.generateFlights(7L, null);

            assertThat(generated.get(0).departureTime()).isEqualTo(TODAY.atTime(22, 30));
            assertThat(generated.get(0).arrivalTime()).isEqualTo(TODAY.plusDays(1).atTime(6, 15));
        }

        @Test
        void anInstanceThatAlreadyExistsIsSkippedSoReRunsAreNoOps() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightRepository.existsByFlightNumberAndDepartureTime(any(), any())).thenReturn(false);
            when(flightRepository.existsByFlightNumberAndDepartureTime("BA178", TODAY.atTime(DEPARTS)))
                    .thenReturn(true);

            List<FlightResponse> generated = scheduleService.generateFlights(7L, null);

            assertThat(generated).hasSize(6);
            assertThat(generated).extracting(FlightResponse::departureTime)
                    .doesNotContain(TODAY.atTime(DEPARTS));
        }

        @Test
        void aFullyGeneratedWindowSavesNoFlightsAtAll() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            when(flightRepository.existsByFlightNumberAndDepartureTime(any(), any())).thenReturn(true);

            assertThat(scheduleService.generateFlights(7L, null)).isEmpty();

            verify(flightRepository, never()).saveAll(anyList());
            // The high-water mark still advances - the window really is covered.
            assertThat(active.getLastGeneratedDate()).isEqualTo(TODAY.plusDays(6));
        }

        @Test
        void generationResumesFromTheDayAfterTheHighWaterMark() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setLastGeneratedDate(TODAY.plusDays(5));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            List<FlightResponse> generated = scheduleService.generateFlights(7L, 3);

            assertThat(generated).hasSize(4);
            assertThat(generated.get(0).departureTime().toLocalDate()).isEqualTo(TODAY.plusDays(6));
            assertThat(generated.get(3).departureTime().toLocalDate()).isEqualTo(TODAY.plusDays(9));
            assertThat(active.getLastGeneratedDate()).isEqualTo(TODAY.plusDays(9));
        }

        @Test
        void aBacklogFromThePastIsNeverBackfilled() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setValidFrom(TODAY.minusDays(30));
            active.setLastGeneratedDate(TODAY.minusDays(10));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            List<FlightResponse> generated = scheduleService.generateFlights(7L, 2);

            assertThat(generated).hasSize(3);
            assertThat(generated.get(0).departureTime().toLocalDate()).isEqualTo(TODAY);
        }

        @Test
        void aFutureValidFromIsHonouredRatherThanClampedToToday() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setValidFrom(TODAY.plusDays(10));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            List<FlightResponse> generated = scheduleService.generateFlights(7L, 1);

            assertThat(generated).hasSize(2);
            assertThat(generated.get(0).departureTime().toLocalDate()).isEqualTo(TODAY.plusDays(10));
        }

        @Test
        void anExplicitHorizonOverridesTheSchedulesOwn() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            assertThat(scheduleService.generateFlights(7L, 0)).hasSize(1);
            assertThat(active.getLastGeneratedDate()).isEqualTo(TODAY);
        }

        @Test
        void theWindowStopsAtValidToAndRetiresTheSchedule() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setValidTo(TODAY.plusDays(2));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            List<FlightResponse> generated = scheduleService.generateFlights(7L, null);

            assertThat(generated).hasSize(3);
            assertThat(generated.get(2).departureTime().toLocalDate()).isEqualTo(TODAY.plusDays(2));
            assertThat(active.getLastGeneratedDate()).isEqualTo(TODAY.plusDays(2));
            assertThat(active.getStatus()).isEqualTo(ScheduleStatus.COMPLETED);
            verify(flightScheduleRepository).save(active);
        }

        @Test
        void anOpenEndedScheduleStaysActiveForever() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));
            nothingGeneratedYet();

            scheduleService.generateFlights(7L, null);

            assertThat(active.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);
        }

        @Test
        void anExpiredScheduleIsRetiredWithoutGeneratingAnything() {
            FlightSchedule active = schedule(7L, ScheduleStatus.ACTIVE);
            active.setValidFrom(TODAY.minusDays(30));
            active.setValidTo(TODAY.minusDays(1));
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(active));

            assertThat(scheduleService.generateFlights(7L, null)).isEmpty();

            assertThat(active.getStatus()).isEqualTo(ScheduleStatus.COMPLETED);
            assertThat(active.getLastGeneratedDate()).isNull();
            verify(flightScheduleRepository).save(active);
            verify(flightRepository, never()).saveAll(anyList());
        }

        @Test
        void pausedSchedulesGenerateNothingAndAreLeftUntouched() {
            FlightSchedule paused = schedule(7L, ScheduleStatus.PAUSED);
            when(flightScheduleRepository.findById(7L)).thenReturn(Optional.of(paused));

            assertThat(scheduleService.generateFlights(7L, null)).isEmpty();

            verify(flightRepository, never()).saveAll(anyList());
            verify(flightScheduleRepository, never()).save(any());
            assertThat(paused.getLastGeneratedDate()).isNull();
        }

        @Test
        void cancelledAndCompletedSchedulesAreSkippedToo() {
            when(flightScheduleRepository.findById(1L))
                    .thenReturn(Optional.of(schedule(1L, ScheduleStatus.CANCELLED)));
            when(flightScheduleRepository.findById(2L))
                    .thenReturn(Optional.of(schedule(2L, ScheduleStatus.COMPLETED)));

            assertThat(scheduleService.generateFlights(1L, null)).isEmpty();
            assertThat(scheduleService.generateFlights(2L, null)).isEmpty();

            verify(flightRepository, never()).saveAll(anyList());
        }

        @Test
        void generateThrowsWhenTheScheduleIsMissing() {
            when(flightScheduleRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.generateFlights(42L, null))
                    .isInstanceOf(FlightScheduleNotFoundException.class);
        }

        @Test
        void theNightlySweepRoutesEachScheduleBackThroughTheProxy() {
            when(flightScheduleRepository.findByStatus(ScheduleStatus.ACTIVE)).thenReturn(List.of(
                    schedule(1L, ScheduleStatus.ACTIVE), schedule(2L, ScheduleStatus.ACTIVE)));

            scheduleService.generateFlightsForAllActiveSchedules();

            // Through `self`, so each schedule generates in its own transaction.
            verify(self).generateFlights(1L, null);
            verify(self).generateFlights(2L, null);
            verify(flightScheduleRepository, never()).findById(any());
        }

        @Test
        void theNightlySweepIsAQuietNoOpWithoutActiveSchedules() {
            when(flightScheduleRepository.findByStatus(ScheduleStatus.ACTIVE)).thenReturn(List.of());

            scheduleService.generateFlightsForAllActiveSchedules();

            verify(self, never()).generateFlights(any(), any());
        }
    }
}
