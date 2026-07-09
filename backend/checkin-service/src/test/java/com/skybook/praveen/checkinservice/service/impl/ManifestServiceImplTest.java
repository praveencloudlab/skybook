package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.CheckInValidator;
import com.skybook.praveen.checkinservice.dto.response.FlightManifestResponse;
import com.skybook.praveen.checkinservice.entity.Baggage;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.entity.FlightManifest;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.enums.ManifestStatus;
import com.skybook.praveen.checkinservice.repository.BaggageRepository;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import com.skybook.praveen.checkinservice.repository.FlightManifestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManifestServiceImplTest {

    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 7, 8, 18, 0);

    @Mock
    private CheckInRepository checkInRepository;
    @Mock
    private BaggageRepository baggageRepository;
    @Mock
    private FlightManifestRepository flightManifestRepository;

    private ManifestServiceImpl manifestService;

    @BeforeEach
    void setUp() {
        manifestService = new ManifestServiceImpl(
                checkInRepository, baggageRepository, flightManifestRepository,
                new CheckInValidator(24, 45, 45, 20), 20);

        lenient().when(flightManifestRepository.save(any(FlightManifest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(baggageRepository.findByCheckInFlightId(7L)).thenReturn(List.of());
    }

    private CheckIn checkIn(CheckInStatus status) {
        return CheckIn.builder()
                .id(1L).bookingId(42L).bookingReference("SBTEST").bookingPassengerId(100L)
                .flightId(7L).passengerName("Test Passenger").departureTime(DEPARTURE).status(status)
                .build();
    }

    @Test
    void getManifestWithNoPersistedRowComputesALiveOpenView() {
        when(flightManifestRepository.findByFlightId(7L)).thenReturn(Optional.empty());
        when(checkInRepository.findByFlightId(7L)).thenReturn(List.of(
                checkIn(CheckInStatus.CHECKED_IN), checkIn(CheckInStatus.BOARDED), checkIn(CheckInStatus.NO_SHOW)));

        FlightManifestResponse response = manifestService.getManifest(7L);

        assertThat(response.status()).isEqualTo(ManifestStatus.OPEN);
        assertThat(response.checkedInCount()).isEqualTo(2); // CHECKED_IN + BOARDED
        assertThat(response.boardedCount()).isEqualTo(1);
        assertThat(response.noShowCount()).isEqualTo(1);
    }

    @Test
    void getManifestExcludesCancelledRows() {
        when(flightManifestRepository.findByFlightId(7L)).thenReturn(Optional.empty());
        when(checkInRepository.findByFlightId(7L)).thenReturn(List.of(
                checkIn(CheckInStatus.CHECKED_IN), checkIn(CheckInStatus.CANCELLED)));

        FlightManifestResponse response = manifestService.getManifest(7L);

        assertThat(response.passengers()).hasSize(1);
        assertThat(response.checkedInCount()).isEqualTo(1);
    }

    @Test
    void finalizeManifestBeforeGateCloseThrows() {
        when(flightManifestRepository.findByFlightId(7L)).thenReturn(Optional.empty());
        when(checkInRepository.findByFlightId(7L)).thenReturn(List.of(checkIn(CheckInStatus.CHECKED_IN)));

        LocalDateTime tooEarly = DEPARTURE.minusMinutes(21); // before the 20m-before gate close

        assertThatThrownBy(() -> manifestService.finalizeManifest(7L, tooEarly))
                .isInstanceOf(IllegalStateException.class);
        verify(flightManifestRepository, never()).save(any());
    }

    @Test
    void finalizeManifestAfterGateCloseSucceedsAndFreezesCounts() {
        when(flightManifestRepository.findByFlightId(7L)).thenReturn(Optional.empty());
        when(checkInRepository.findByFlightId(7L)).thenReturn(List.of(
                checkIn(CheckInStatus.BOARDED), checkIn(CheckInStatus.NO_SHOW)));

        LocalDateTime afterGateClose = DEPARTURE.minusMinutes(19);

        FlightManifestResponse response = manifestService.finalizeManifest(7L, afterGateClose);

        assertThat(response.status()).isEqualTo(ManifestStatus.FINALIZED);
        assertThat(response.finalizedAt()).isEqualTo(afterGateClose);
        assertThat(response.boardedCount()).isEqualTo(1);
        assertThat(response.noShowCount()).isEqualTo(1);
    }

    @Test
    void finalizeManifestWithNoCheckInsFinalizesImmediately() {
        // No departure time to validate a cutoff against - an empty
        // manifest is a legitimate outcome (design doc section 5.7).
        when(flightManifestRepository.findByFlightId(7L)).thenReturn(Optional.empty());
        when(checkInRepository.findByFlightId(7L)).thenReturn(List.of());

        FlightManifestResponse response = manifestService.finalizeManifest(7L, LocalDateTime.now());

        assertThat(response.status()).isEqualTo(ManifestStatus.FINALIZED);
        assertThat(response.checkedInCount()).isZero();
    }

    @Test
    void finalizeManifestIsIdempotentWhenAlreadyFinalized() {
        FlightManifest alreadyFinalized = FlightManifest.builder()
                .flightId(7L).status(ManifestStatus.FINALIZED).finalizedAt(DEPARTURE)
                .checkedInCount(5).boardedCount(5).noShowCount(0).baggageCount(0)
                .baggageWeightKg(BigDecimal.ZERO)
                .build();
        when(flightManifestRepository.findByFlightId(7L)).thenReturn(Optional.of(alreadyFinalized));
        when(checkInRepository.findByFlightId(7L)).thenReturn(List.of());

        FlightManifestResponse response = manifestService.finalizeManifest(7L, LocalDateTime.now());

        assertThat(response.status()).isEqualTo(ManifestStatus.FINALIZED);
        verify(flightManifestRepository, never()).save(any());
    }

    @Test
    void finalizeDueManifestsSkipsFlightsAlreadyFinalized() {
        when(checkInRepository.findDistinctFlightIdByDepartureTimeBefore(any())).thenReturn(List.of(7L, 8L));

        FlightManifest finalized7 = FlightManifest.builder().flightId(7L).status(ManifestStatus.FINALIZED).build();
        when(flightManifestRepository.findByFlightId(7L)).thenReturn(Optional.of(finalized7));
        when(flightManifestRepository.findByFlightId(8L)).thenReturn(Optional.empty());
        when(checkInRepository.findByFlightId(8L)).thenReturn(List.of());

        int finalizedCount = manifestService.finalizeDueManifests();

        assertThat(finalizedCount).isEqualTo(1); // only flight 8 newly finalized
    }
}
