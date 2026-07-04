package com.skybook.praveen.inventoryservice.service;

import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.InventorySearchRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.InventoryHistoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;

import java.util.List;

/**
 * Owns the FlightInventory aggregate: creation, counts, holds, lifecycle.
 * Knows nothing about flight-service (the facade validates flights before
 * calling in) or Kafka (the facade publishes after commit) - same division
 * of labour as BookingService vs BookingFacade.
 */
public interface InventoryService {

    /** Caller (facade) must have validated the flight exists and is bookable. */
    FlightInventoryResponse createInventory(CreateFlightInventoryRequest request);

    FlightInventoryResponse getByFlightId(Long flightId);

    List<FlightInventoryResponse> search(InventorySearchRequest criteria);

    List<InventoryHistoryResponse> getHistory(Long flightId);

    SeatHoldResponse holdSeat(HoldSeatRequest request);

    SeatHoldResponse releaseHold(ReleaseSeatRequest request);

    FlightInventoryResponse closeInventory(Long flightId, String reason);

    FlightInventoryResponse reopenInventory(Long flightId, String reason);

    /** Sweeps ACTIVE holds past their TTL (called by SeatHoldExpiryJob). Returns how many expired. */
    int expireHolds();
}
