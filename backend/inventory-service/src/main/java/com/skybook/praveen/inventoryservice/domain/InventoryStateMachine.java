package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.InventoryHistory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.entity.SeatReservation;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates and applies transitions for the three inventory state machines
 * (SeatHold, SeatReservation, FlightInventory) and records every transition
 * onto {@code FlightInventory.history} in-memory.
 *
 * Same design as booking-service's BookingStateMachine: no repository
 * dependency, only mutates the entities handed to it; history rows persist
 * via FlightInventory's cascade when the caller saves the aggregate.
 */
@Component
public class InventoryStateMachine {

    private static final Map<SeatHoldStatus, Set<SeatHoldStatus>> HOLD_TRANSITIONS = new EnumMap<>(SeatHoldStatus.class);
    private static final Map<SeatReservationStatus, Set<SeatReservationStatus>> RESERVATION_TRANSITIONS = new EnumMap<>(SeatReservationStatus.class);
    private static final Map<InventoryStatus, Set<InventoryStatus>> INVENTORY_TRANSITIONS = new EnumMap<>(InventoryStatus.class);

    static {
        HOLD_TRANSITIONS.put(SeatHoldStatus.ACTIVE,
                EnumSet.of(SeatHoldStatus.CONFIRMED, SeatHoldStatus.RELEASED, SeatHoldStatus.EXPIRED));
        HOLD_TRANSITIONS.put(SeatHoldStatus.CONFIRMED, EnumSet.noneOf(SeatHoldStatus.class));
        HOLD_TRANSITIONS.put(SeatHoldStatus.RELEASED, EnumSet.noneOf(SeatHoldStatus.class));
        HOLD_TRANSITIONS.put(SeatHoldStatus.EXPIRED, EnumSet.noneOf(SeatHoldStatus.class));

        RESERVATION_TRANSITIONS.put(SeatReservationStatus.RESERVED, EnumSet.of(SeatReservationStatus.CANCELLED));
        RESERVATION_TRANSITIONS.put(SeatReservationStatus.CANCELLED, EnumSet.noneOf(SeatReservationStatus.class));

        INVENTORY_TRANSITIONS.put(InventoryStatus.OPEN, EnumSet.of(InventoryStatus.SOLD_OUT, InventoryStatus.CLOSED));
        INVENTORY_TRANSITIONS.put(InventoryStatus.SOLD_OUT, EnumSet.of(InventoryStatus.OPEN, InventoryStatus.CLOSED));
        INVENTORY_TRANSITIONS.put(InventoryStatus.CLOSED, EnumSet.of(InventoryStatus.OPEN));
    }

    // ---------------------------------------------------------------
    // SeatHold
    // ---------------------------------------------------------------

    public boolean canTransitionHold(SeatHoldStatus from, SeatHoldStatus to) {
        return HOLD_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void transitionHold(SeatHold hold, SeatHoldStatus to, String details) {

        SeatHoldStatus from = hold.getStatus();

        if (!canTransitionHold(from, to)) {
            throw new IllegalStateException("Cannot transition seat hold from " + from + " to " + to);
        }

        hold.setStatus(to);

        InventoryHistoryType type = switch (to) {
            case RELEASED -> InventoryHistoryType.HOLD_RELEASED;
            case EXPIRED -> InventoryHistoryType.HOLD_EXPIRED;
            case CONFIRMED -> InventoryHistoryType.SEAT_RESERVED;
            case ACTIVE -> throw new IllegalStateException("ACTIVE is not a transition target");
        };

        recordHistory(hold.getFlightInventory(), type, hold.getAircraftSeat(), hold.getBookingId(), details);
    }

    // ---------------------------------------------------------------
    // SeatReservation
    // ---------------------------------------------------------------

    public boolean canTransitionReservation(SeatReservationStatus from, SeatReservationStatus to) {
        return RESERVATION_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void cancelReservation(SeatReservation reservation, String details) {

        SeatReservationStatus from = reservation.getStatus();

        if (!canTransitionReservation(from, SeatReservationStatus.CANCELLED)) {
            throw new IllegalStateException("Cannot transition reservation from " + from + " to CANCELLED");
        }

        reservation.setStatus(SeatReservationStatus.CANCELLED);
        reservation.setCancelledAt(LocalDateTime.now());

        recordHistory(reservation.getFlightInventory(), InventoryHistoryType.RESERVATION_CANCELLED,
                reservation.getAircraftSeat(), reservation.getBookingId(), details);
    }

    // ---------------------------------------------------------------
    // FlightInventory
    // ---------------------------------------------------------------

    public boolean canTransitionInventory(InventoryStatus from, InventoryStatus to) {
        return INVENTORY_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void transitionInventory(FlightInventory inventory, InventoryStatus to, String details) {

        InventoryStatus from = inventory.getStatus();

        if (!canTransitionInventory(from, to)) {
            throw new IllegalStateException("Cannot transition inventory from " + from + " to " + to);
        }

        inventory.setStatus(to);

        InventoryHistoryType type = switch (to) {
            case SOLD_OUT -> InventoryHistoryType.INVENTORY_SOLD_OUT;
            case CLOSED -> InventoryHistoryType.INVENTORY_CLOSED;
            case OPEN -> InventoryHistoryType.INVENTORY_REOPENED;
        };

        recordHistory(inventory, type, null, null, details);
    }

    // ---------------------------------------------------------------
    // History
    // ---------------------------------------------------------------

    /** Also used by the service layer for non-transition events (SEAT_HELD, INVENTORY_CREATED). */
    public void recordHistory(FlightInventory inventory, InventoryHistoryType type,
                              AircraftSeat seat, Long bookingId, String details) {

        InventoryHistory entry = InventoryHistory.builder()
                .flightInventory(inventory)
                .historyType(type)
                .aircraftSeat(seat)
                .bookingId(bookingId)
                .details(details)
                .changedAt(LocalDateTime.now())
                .build();

        inventory.getHistory().add(entry);
    }
}
