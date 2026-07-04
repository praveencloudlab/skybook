package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.exception.AircraftNotFoundException;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AircraftServiceImplTest {

    @Mock
    private AircraftRepository aircraftRepository;

    private AircraftServiceImpl aircraftService;

    @BeforeEach
    void setUp() {
        aircraftService = new AircraftServiceImpl(aircraftRepository);
    }

    private Aircraft aircraft(Long id, AircraftStatus status) {
        return Aircraft.builder()
                .id(id)
                .registrationNumber("VT-SKB")
                .manufacturer("Airbus")
                .model("A320neo")
                .totalSeats(0)
                .status(status)
                .build();
    }

    @Test
    void createSavesAndReturnsResponse() {
        CreateAircraftRequest request = new CreateAircraftRequest("VT-SKB", "Airbus", "A320neo");
        when(aircraftRepository.existsByRegistrationNumber("VT-SKB")).thenReturn(false);
        when(aircraftRepository.save(any(Aircraft.class))).thenAnswer(inv -> inv.getArgument(0));

        AircraftResponse response = aircraftService.createAircraft(request);

        ArgumentCaptor<Aircraft> captor = ArgumentCaptor.forClass(Aircraft.class);
        verify(aircraftRepository).save(captor.capture());
        assertThat(captor.getValue().getRegistrationNumber()).isEqualTo("VT-SKB");
        assertThat(response.manufacturer()).isEqualTo("Airbus");
        assertThat(response.model()).isEqualTo("A320neo");
    }

    @Test
    void createWithDuplicateRegistrationThrowsConflict() {
        when(aircraftRepository.existsByRegistrationNumber("VT-SKB")).thenReturn(true);

        assertThatThrownBy(() -> aircraftService.createAircraft(
                new CreateAircraftRequest("VT-SKB", "Airbus", "A320neo")))
                .isInstanceOf(InventoryConflictException.class)
                .hasMessageContaining("VT-SKB");

        verify(aircraftRepository, never()).save(any());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(aircraftRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aircraftService.getAircraftById(42L))
                .isInstanceOf(AircraftNotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    void getByRegistrationThrowsWhenMissing() {
        when(aircraftRepository.findByRegistrationNumber("VT-XXX")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aircraftService.getAircraftByRegistrationNumber("VT-XXX"))
                .isInstanceOf(AircraftNotFoundException.class)
                .hasMessageContaining("VT-XXX");
    }

    @Test
    void statusUpdateAppliesTransition() {
        Aircraft active = aircraft(1L, AircraftStatus.ACTIVE);
        when(aircraftRepository.findById(1L)).thenReturn(Optional.of(active));

        AircraftResponse response = aircraftService.updateAircraftStatus(1L, AircraftStatus.MAINTENANCE);

        assertThat(response.status()).isEqualTo(AircraftStatus.MAINTENANCE);
        assertThat(active.getStatus()).isEqualTo(AircraftStatus.MAINTENANCE);
    }

    @Test
    void retiredIsTerminal() {
        when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft(1L, AircraftStatus.RETIRED)));

        assertThatThrownBy(() -> aircraftService.updateAircraftStatus(1L, AircraftStatus.ACTIVE))
                .isInstanceOf(InventoryConflictException.class)
                .hasMessageContaining("RETIRED");
    }
}
