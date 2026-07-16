package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.domain.InventoryStateMachine;
import com.skybook.praveen.inventoryservice.domain.SeatAllocationValidator;
import com.skybook.praveen.inventoryservice.domain.SeatHoldExpiryCalculator;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.entity.SeatReservation;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyHeldException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyReservedException;
import com.skybook.praveen.inventoryservice.exception.SeatCabinMismatchException;
import com.skybook.praveen.inventoryservice.exception.SeatHoldExpiredException;
import com.skybook.praveen.inventoryservice.exception.SeatNotAvailableException;
import com.skybook.praveen.inventoryservice.mapper.SeatReservationMapper;
import com.skybook.praveen.inventoryservice.repository.SeatHoldRepository;
import com.skybook.praveen.inventoryservice.repository.SeatReservationRepository;
import com.skybook.praveen.inventoryservice.service.SeatReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReservationServiceImpl implements SeatReservationService {

    private final SeatReservationRepository seatReservationRepository;
    private final SeatHoldRepository seatHoldRepository;

    private final InventoryStateMachine stateMachine;
    private final SeatAllocationValidator allocationValidator;
    private final SeatHoldExpiryCalculator expiryCalculator;

    // Reuses the aggregate lookups and count bookkeeping so the invariant
    // (available + held + reserved + blocked == total) lives in one class.
    private final InventoryServiceImpl inventoryService;

    @Override
    @Transactional
    public SeatReservationResponse reserveSeat(ReserveSeatRequest request) {

        // Shared pessimistic flight lock (§5.3): reservations mutate the same
        // counters as holds, so they serialize on the same lock - and the §9
        // check-in cabin/ceiling validation below runs under it.
        FlightInventory inventory = inventoryService.findByFlightIdForUpdate(request.flightId());
        AircraftSeat seat = inventoryService.findSeat(inventory, request.seatNumber());

        if (seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                inventory.getId(), seat.getId(), SeatReservationStatus.RESERVED)) {
            throw new SeatAlreadyReservedException(request.flightId(), request.seatNumber());
        }

        SeatHold hold = resolveHold(request, inventory, seat);

        if (hold != null) {
            reserveFromHold(request, inventory, hold);
        } else {
            reserveDirect(request, inventory, seat);
        }

        SeatReservation reservation = SeatReservation.builder()
                .flightInventory(inventory)
                .aircraftSeat(seat)
                .bookingId(request.bookingId())
                .bookingPassengerId(request.bookingPassengerId())
                .originatingHold(hold)
                .build();

        SeatReservation saved = seatReservationRepository.save(reservation);
        log.info("Reserved seat {} on flight {} for booking {} ({})",
                request.seatNumber(), request.flightId(), request.bookingId(),
                hold != null ? "from hold " + hold.getId() : "direct");

        return SeatReservationMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public SeatReservationResponse cancelReservation(ReleaseSeatRequest request) {

        FlightInventory inventory = inventoryService.findByFlightId(request.flightId());
        AircraftSeat seat = inventoryService.findSeat(inventory, request.seatNumber());

        SeatReservation reservation = seatReservationRepository
                .findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                        inventory.getId(), seat.getId(), SeatReservationStatus.RESERVED)
                .orElseThrow(() -> new InventoryConflictException(
                        "No active reservation on seat " + request.seatNumber()
                                + " for flight " + request.flightId()));

        if (!reservation.getBookingId().equals(request.bookingId())) {
            throw new InventoryConflictException("Seat " + request.seatNumber()
                    + " is reserved by a different booking");
        }

        stateMachine.cancelReservation(reservation,
                request.reason() != null ? request.reason() : "Cancelled by booking " + request.bookingId());

        inventoryService.returnSeatToPool(inventory, false);

        log.info("Cancelled reservation of seat {} on flight {} (booking {})",
                request.seatNumber(), request.flightId(), request.bookingId());

        return SeatReservationMapper.toResponse(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeatReservationResponse> getReservationsByBooking(Long bookingId) {
        return seatReservationRepository.findByBookingId(bookingId)
                .stream().map(SeatReservationMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeatReservationResponse> getReservationsByFlight(Long flightId) {
        FlightInventory inventory = inventoryService.findByFlightId(flightId);
        return seatReservationRepository.findByFlightInventoryIdAndStatus(
                        inventory.getId(), SeatReservationStatus.RESERVED)
                .stream().map(SeatReservationMapper::toResponse).toList();
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    /**
     * Explicit holdId wins; otherwise look for the booking's own ACTIVE hold
     * on this seat. A hold belonging to another booking blocks the seat.
     */
    private SeatHold resolveHold(ReserveSeatRequest request, FlightInventory inventory, AircraftSeat seat) {

        SeatHold hold;

        if (request.holdId() != null) {
            hold = seatHoldRepository.findById(request.holdId())
                    .orElseThrow(() -> new InventoryConflictException(
                            "Seat hold not found with id: " + request.holdId()));

            if (!hold.getFlightInventory().getId().equals(inventory.getId())
                    || !hold.getAircraftSeat().getId().equals(seat.getId())) {
                throw new InventoryConflictException(
                        "Hold " + request.holdId() + " is for a different flight/seat");
            }
        } else {
            hold = seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    inventory.getId(), seat.getId(), SeatHoldStatus.ACTIVE).orElse(null);
        }

        if (hold != null && !hold.getBookingId().equals(request.bookingId())) {
            throw new SeatAlreadyHeldException(request.flightId(), request.seatNumber());
        }

        return hold;
    }

    private void reserveFromHold(ReserveSeatRequest request, FlightInventory inventory, SeatHold hold) {

        if (hold.getStatus() != SeatHoldStatus.ACTIVE) {
            throw new InventoryConflictException("Hold " + hold.getId() + " is " + hold.getStatus());
        }
        if (expiryCalculator.isExpired(hold.getExpiresAt(), LocalDateTime.now())) {
            throw new SeatHoldExpiredException(hold.getId());
        }

        // Seat was already taken out of 'available' when the hold was placed.
        stateMachine.transitionHold(hold, SeatHoldStatus.CONFIRMED,
                "Confirmed into reservation for booking " + request.bookingId());
        inventory.setHeldSeats(inventory.getHeldSeats() - 1);
        inventory.setReservedSeats(inventory.getReservedSeats() + 1);
    }

    private void reserveDirect(ReserveSeatRequest request, FlightInventory inventory, AircraftSeat seat) {

        allocationValidator.validateInventoryOpen(inventory);
        allocationValidator.validateSeatUsable(seat);

        // Contained-v1 check-in rule (§9, round-7 contract): the DIRECT path
        // enforces cabin + entitlement ceiling when the caller supplies them
        // (check-in does; booking confirmation goes through a hold and never
        // reaches here with these fields). Inventory is authoritative - the
        // caller's own comparison is UX, this is the enforcement.
        if (request.travelClass() != null && seat.getSeatType() != request.travelClass()) {
            throw new SeatCabinMismatchException(seat.getSeatNumber(), seat.getSeatType(), request.travelClass());
        }
        if (request.maxAllowedSurcharge() != null) {
            BigDecimal listed = inventoryService.listedSurchargeFor(inventory, seat);
            if (listed.compareTo(request.maxAllowedSurcharge()) > 0) {
                throw new InventoryConflictException("Seat " + request.seatNumber()
                        + " lists at " + listed + " which exceeds the passenger's paid entitlement of "
                        + request.maxAllowedSurcharge() + " - paid upgrades are a Manage-Booking flow, not check-in");
            }
        }

        if (inventory.getAvailableSeats() <= 0) {
            throw new SeatNotAvailableException(request.flightId(), request.seatNumber(),
                    "no available seats left");
        }

        inventory.setAvailableSeats(inventory.getAvailableSeats() - 1);
        inventory.setReservedSeats(inventory.getReservedSeats() + 1);

        stateMachine.recordHistory(inventory, InventoryHistoryType.SEAT_RESERVED, seat,
                request.bookingId(), "Direct reservation (no hold)");

        inventoryService.markSoldOutIfFull(inventory);
    }
}
