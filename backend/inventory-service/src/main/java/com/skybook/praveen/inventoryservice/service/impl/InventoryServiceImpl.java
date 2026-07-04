package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.domain.InventoryStateMachine;
import com.skybook.praveen.inventoryservice.domain.SeatAllocationValidator;
import com.skybook.praveen.inventoryservice.domain.SeatHoldExpiryCalculator;
import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.InventorySearchRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.InventoryHistoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.exception.AircraftNotFoundException;
import com.skybook.praveen.inventoryservice.exception.AircraftSeatNotFoundException;
import com.skybook.praveen.inventoryservice.exception.FlightInventoryNotFoundException;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyHeldException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyReservedException;
import com.skybook.praveen.inventoryservice.exception.SeatNotAvailableException;
import com.skybook.praveen.inventoryservice.mapper.FlightInventoryMapper;
import com.skybook.praveen.inventoryservice.mapper.InventoryHistoryMapper;
import com.skybook.praveen.inventoryservice.mapper.SeatHoldMapper;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import com.skybook.praveen.inventoryservice.repository.FlightInventoryRepository;
import com.skybook.praveen.inventoryservice.repository.InventoryHistoryRepository;
import com.skybook.praveen.inventoryservice.repository.SeatHoldRepository;
import com.skybook.praveen.inventoryservice.repository.SeatReservationRepository;
import com.skybook.praveen.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final FlightInventoryRepository flightInventoryRepository;
    private final AircraftRepository aircraftRepository;
    private final AircraftSeatRepository aircraftSeatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final InventoryHistoryRepository inventoryHistoryRepository;

    private final InventoryStateMachine stateMachine;
    private final SeatAllocationValidator allocationValidator;
    private final SeatHoldExpiryCalculator expiryCalculator;

    // ---------------------------------------------------------------
    // Creation / reads
    // ---------------------------------------------------------------

    @Override
    @Transactional
    public FlightInventoryResponse createInventory(CreateFlightInventoryRequest request) {

        if (flightInventoryRepository.existsByFlightId(request.flightId())) {
            throw new InventoryConflictException(
                    "Inventory already exists for flight id: " + request.flightId());
        }

        Aircraft aircraft = aircraftRepository.findById(request.aircraftId())
                .orElseThrow(() -> new AircraftNotFoundException(request.aircraftId()));

        if (aircraft.getStatus() != AircraftStatus.ACTIVE) {
            throw new InventoryConflictException("Aircraft " + aircraft.getRegistrationNumber()
                    + " is " + aircraft.getStatus() + " - cannot build inventory against it");
        }

        int sellableSeats = (int) aircraftSeatRepository
                .countByAircraftIdAndStatus(aircraft.getId(), AircraftSeatStatus.ACTIVE);

        if (sellableSeats == 0) {
            throw new InventoryConflictException("Aircraft " + aircraft.getRegistrationNumber()
                    + " has no ACTIVE seats - create its seat map first");
        }

        int blocked = request.blockedSeats() == null ? 0 : request.blockedSeats();
        if (blocked > sellableSeats) {
            throw new InventoryConflictException(
                    "blockedSeats (" + blocked + ") exceeds sellable seats (" + sellableSeats + ")");
        }

        FlightInventory inventory = FlightInventory.builder()
                .flightId(request.flightId())
                .aircraft(aircraft)
                .totalSeats(sellableSeats)
                .blockedSeats(blocked)
                .availableSeats(sellableSeats - blocked)
                .heldSeats(0)
                .reservedSeats(0)
                .build();

        stateMachine.recordHistory(inventory, InventoryHistoryType.INVENTORY_CREATED, null, null,
                "Inventory created against aircraft " + aircraft.getRegistrationNumber()
                        + " (" + sellableSeats + " sellable, " + blocked + " blocked)");

        FlightInventory saved = flightInventoryRepository.save(inventory);
        log.info("Created inventory for flight {} on aircraft {} ({} seats)",
                saved.getFlightId(), aircraft.getRegistrationNumber(), sellableSeats);

        return FlightInventoryMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public FlightInventoryResponse getByFlightId(Long flightId) {
        return FlightInventoryMapper.toResponse(findByFlightId(flightId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlightInventoryResponse> search(InventorySearchRequest criteria) {
        // Volume is bounded (one row per flight), so in-memory filtering over
        // findAll keeps this free of Specification machinery - same trade-off
        // as booking-service's search.
        return flightInventoryRepository.findAll().stream()
                .filter(inv -> criteria.flightId() == null || inv.getFlightId().equals(criteria.flightId()))
                .filter(inv -> criteria.aircraftId() == null || inv.getAircraft().getId().equals(criteria.aircraftId()))
                .filter(inv -> criteria.status() == null || inv.getStatus() == criteria.status())
                .filter(inv -> criteria.minAvailableSeats() == null
                        || inv.getAvailableSeats() >= criteria.minAvailableSeats())
                .map(FlightInventoryMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryHistoryResponse> getHistory(Long flightId) {
        FlightInventory inventory = findByFlightId(flightId);
        return inventoryHistoryRepository.findByFlightInventoryIdOrderByChangedAtAsc(inventory.getId())
                .stream().map(InventoryHistoryMapper::toResponse).toList();
    }

    // ---------------------------------------------------------------
    // Holds
    // ---------------------------------------------------------------

    @Override
    @Transactional
    public SeatHoldResponse holdSeat(HoldSeatRequest request) {

        FlightInventory inventory = findByFlightId(request.flightId());
        AircraftSeat seat = findSeat(inventory, request.seatNumber());

        allocationValidator.validateInventoryOpen(inventory);
        allocationValidator.validateSeatUsable(seat);

        if (seatHoldRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                inventory.getId(), seat.getId(), SeatHoldStatus.ACTIVE)) {
            throw new SeatAlreadyHeldException(request.flightId(), request.seatNumber());
        }
        if (seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                inventory.getId(), seat.getId(), SeatReservationStatus.RESERVED)) {
            throw new SeatAlreadyReservedException(request.flightId(), request.seatNumber());
        }
        if (inventory.getAvailableSeats() <= 0) {
            throw new SeatNotAvailableException(request.flightId(), request.seatNumber(),
                    "no available seats left");
        }

        LocalDateTime now = LocalDateTime.now();

        SeatHold hold = SeatHold.builder()
                .flightInventory(inventory)
                .aircraftSeat(seat)
                .bookingId(request.bookingId())
                .heldAt(now)
                .expiresAt(expiryCalculator.calculateExpiry(now))
                .build();

        inventory.setAvailableSeats(inventory.getAvailableSeats() - 1);
        inventory.setHeldSeats(inventory.getHeldSeats() + 1);

        stateMachine.recordHistory(inventory, InventoryHistoryType.SEAT_HELD, seat,
                request.bookingId(), "Hold expires at " + hold.getExpiresAt());

        markSoldOutIfFull(inventory);

        SeatHold saved = seatHoldRepository.save(hold);
        log.info("Held seat {} on flight {} for booking {} until {}",
                request.seatNumber(), request.flightId(), request.bookingId(), saved.getExpiresAt());

        return SeatHoldMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public SeatHoldResponse releaseHold(ReleaseSeatRequest request) {

        FlightInventory inventory = findByFlightId(request.flightId());
        AircraftSeat seat = findSeat(inventory, request.seatNumber());

        SeatHold hold = seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                        inventory.getId(), seat.getId(), SeatHoldStatus.ACTIVE)
                .orElseThrow(() -> new InventoryConflictException(
                        "No active hold on seat " + request.seatNumber() + " for flight " + request.flightId()));

        if (!hold.getBookingId().equals(request.bookingId())) {
            throw new InventoryConflictException("Seat " + request.seatNumber()
                    + " is held by a different booking");
        }

        stateMachine.transitionHold(hold, SeatHoldStatus.RELEASED,
                request.reason() != null ? request.reason() : "Released by booking " + request.bookingId());

        returnSeatToPool(inventory, true);

        log.info("Released hold on seat {} flight {} (booking {})",
                request.seatNumber(), request.flightId(), request.bookingId());

        return SeatHoldMapper.toResponse(hold);
    }

    @Override
    @Transactional
    public int expireHolds() {

        List<SeatHold> expired = seatHoldRepository.findByStatusAndExpiresAtBefore(
                SeatHoldStatus.ACTIVE, LocalDateTime.now());

        for (SeatHold hold : expired) {
            stateMachine.transitionHold(hold, SeatHoldStatus.EXPIRED,
                    "Expired by SeatHoldExpiryJob (TTL " + expiryCalculator.getTtlMinutes() + "m)");
            returnSeatToPool(hold.getFlightInventory(), true);
        }

        if (!expired.isEmpty()) {
            log.info("Expired {} seat hold(s)", expired.size());
        }

        return expired.size();
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    @Transactional
    public FlightInventoryResponse closeInventory(Long flightId, String reason) {
        FlightInventory inventory = findByFlightId(flightId);
        stateMachine.transitionInventory(inventory, InventoryStatus.CLOSED, reason);
        log.info("Closed inventory for flight {}: {}", flightId, reason);
        return FlightInventoryMapper.toResponse(inventory);
    }

    @Override
    @Transactional
    public FlightInventoryResponse reopenInventory(Long flightId, String reason) {
        FlightInventory inventory = findByFlightId(flightId);
        stateMachine.transitionInventory(inventory, InventoryStatus.OPEN, reason);
        log.info("Reopened inventory for flight {}: {}", flightId, reason);
        return FlightInventoryMapper.toResponse(inventory);
    }

    // ---------------------------------------------------------------
    // Shared internals (package-private for SeatReservationServiceImpl)
    // ---------------------------------------------------------------

    FlightInventory findByFlightId(Long flightId) {
        return flightInventoryRepository.findByFlightId(flightId)
                .orElseThrow(() -> new FlightInventoryNotFoundException(flightId));
    }

    AircraftSeat findSeat(FlightInventory inventory, String seatNumber) {
        return aircraftSeatRepository.findByAircraftIdAndSeatNumber(
                        inventory.getAircraft().getId(), seatNumber)
                .orElseThrow(() -> new AircraftSeatNotFoundException(
                        inventory.getAircraft().getId(), seatNumber));
    }

    /** A held (fromHold=true) or reserved seat goes back to available; reopens a SOLD_OUT inventory. */
    void returnSeatToPool(FlightInventory inventory, boolean fromHold) {

        if (fromHold) {
            inventory.setHeldSeats(inventory.getHeldSeats() - 1);
        } else {
            inventory.setReservedSeats(inventory.getReservedSeats() - 1);
        }
        inventory.setAvailableSeats(inventory.getAvailableSeats() + 1);

        if (inventory.getStatus() == InventoryStatus.SOLD_OUT && inventory.getAvailableSeats() > 0) {
            stateMachine.transitionInventory(inventory, InventoryStatus.OPEN, "Seat returned to pool");
        }
    }

    /** OPEN -> SOLD_OUT once the last available seat is taken. */
    void markSoldOutIfFull(FlightInventory inventory) {
        if (inventory.getStatus() == InventoryStatus.OPEN && inventory.getAvailableSeats() == 0) {
            stateMachine.transitionInventory(inventory, InventoryStatus.SOLD_OUT, "Last available seat taken");
        }
    }
}
