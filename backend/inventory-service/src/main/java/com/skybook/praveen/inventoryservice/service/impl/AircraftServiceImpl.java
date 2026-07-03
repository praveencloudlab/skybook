package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.exception.AircraftNotFoundException;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.mapper.AircraftMapper;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.service.AircraftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AircraftServiceImpl implements AircraftService {

    private final AircraftRepository aircraftRepository;

    @Override
    @Transactional
    public AircraftResponse createAircraft(CreateAircraftRequest request) {

        if (aircraftRepository.existsByRegistrationNumber(request.registrationNumber())) {
            throw new InventoryConflictException(
                    "Aircraft already exists with registration number: " + request.registrationNumber());
        }

        Aircraft aircraft = Aircraft.builder()
                .registrationNumber(request.registrationNumber())
                .manufacturer(request.manufacturer())
                .model(request.model())
                .build();

        Aircraft saved = aircraftRepository.save(aircraft);
        log.info("Created aircraft {} ({} {})", saved.getRegistrationNumber(), saved.getManufacturer(), saved.getModel());

        return AircraftMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AircraftResponse getAircraftById(Long id) {
        return AircraftMapper.toResponse(findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public AircraftResponse getAircraftByRegistrationNumber(String registrationNumber) {
        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(registrationNumber)
                .orElseThrow(() -> new AircraftNotFoundException(registrationNumber));
        return AircraftMapper.toResponse(aircraft);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AircraftResponse> getAllAircraft() {
        return aircraftRepository.findAll().stream().map(AircraftMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AircraftResponse> getAircraftByStatus(AircraftStatus status) {
        return aircraftRepository.findByStatus(status).stream().map(AircraftMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public AircraftResponse updateAircraftStatus(Long id, AircraftStatus newStatus) {

        Aircraft aircraft = findById(id);
        AircraftStatus from = aircraft.getStatus();

        if (from == AircraftStatus.RETIRED) {
            throw new InventoryConflictException(
                    "Aircraft " + aircraft.getRegistrationNumber() + " is RETIRED - status cannot change");
        }

        aircraft.setStatus(newStatus);
        log.info("Aircraft {} status: {} -> {}", aircraft.getRegistrationNumber(), from, newStatus);

        return AircraftMapper.toResponse(aircraft);
    }

    private Aircraft findById(Long id) {
        return aircraftRepository.findById(id).orElseThrow(() -> new AircraftNotFoundException(id));
    }
}
