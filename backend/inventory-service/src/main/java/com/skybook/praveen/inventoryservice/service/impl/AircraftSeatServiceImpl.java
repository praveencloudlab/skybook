package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.domain.SeatMapGenerator;
import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateSeatMapRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.exception.AircraftNotFoundException;
import com.skybook.praveen.inventoryservice.exception.AircraftSeatNotFoundException;
import com.skybook.praveen.inventoryservice.mapper.AircraftMapper;
import com.skybook.praveen.inventoryservice.mapper.AircraftSeatMapper;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import com.skybook.praveen.inventoryservice.service.AircraftSeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AircraftSeatServiceImpl implements AircraftSeatService {

    private final AircraftRepository aircraftRepository;
    private final AircraftSeatRepository aircraftSeatRepository;

    private final SeatMapGenerator seatMapGenerator;

    @Override
    @Transactional
    public AircraftSeatResponse addSeat(Long aircraftId, CreateAircraftSeatRequest request) {

        Aircraft aircraft = findAircraft(aircraftId);

        // Single-seat add reuses the map-level rules (duplicate check etc.).
        List<AircraftSeat> created = seatMapGenerator.generate(aircraft, List.of(request));

        log.info("Added seat {} to aircraft {}", request.seatNumber(), aircraft.getRegistrationNumber());
        return AircraftSeatMapper.toResponse(created.getFirst());
    }

    @Override
    @Transactional
    public List<AircraftSeatResponse> createSeatMap(Long aircraftId, CreateSeatMapRequest request) {

        Aircraft aircraft = findAircraft(aircraftId);

        List<AircraftSeat> created = seatMapGenerator.generate(aircraft, request.seats());

        log.info("Created {} seats on aircraft {} (total now {})",
                created.size(), aircraft.getRegistrationNumber(), aircraft.getTotalSeats());

        return created.stream().map(AircraftSeatMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(Long aircraftId) {
        return AircraftMapper.toSeatMapResponse(findAircraft(aircraftId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AircraftSeatResponse> getSeatsByStatus(Long aircraftId, AircraftSeatStatus status) {
        findAircraft(aircraftId); // 404 for unknown aircraft rather than an empty list
        return aircraftSeatRepository.findByAircraftIdAndStatus(aircraftId, status)
                .stream().map(AircraftSeatMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public AircraftSeatResponse updateSeatStatus(Long aircraftId, String seatNumber, AircraftSeatStatus newStatus) {

        findAircraft(aircraftId);

        AircraftSeat seat = aircraftSeatRepository.findByAircraftIdAndSeatNumber(aircraftId, seatNumber)
                .orElseThrow(() -> new AircraftSeatNotFoundException(aircraftId, seatNumber));

        AircraftSeatStatus from = seat.getStatus();
        seat.setStatus(newStatus);

        log.info("Seat {} on aircraft id {} status: {} -> {}", seatNumber, aircraftId, from, newStatus);
        return AircraftSeatMapper.toResponse(seat);
    }

    private Aircraft findAircraft(Long aircraftId) {
        return aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new AircraftNotFoundException(aircraftId));
    }
}
