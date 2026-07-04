package com.skybook.praveen.inventoryservice.facade;

import com.skybook.praveen.inventoryservice.client.FlightDetails;
import com.skybook.praveen.inventoryservice.client.FlightServiceClient;
import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.producer.InventoryEventProducer;
import com.skybook.praveen.inventoryservice.service.InventoryService;
import com.skybook.praveen.inventoryservice.service.SeatReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Orchestration layer - the only class that knows other services/concerns
 * exist: flight validation via FlightServiceClient, persistence via the two
 * services, events via InventoryEventProducer.
 *
 * Wraps only the operations that need orchestration (create validates the
 * flight; hold/release/reserve/cancel each publish an event). Reads, search,
 * close/reopen have nothing to orchestrate - controllers call the services
 * directly for those, same principle as BookingFacade.
 *
 * Deliberately NOT @Transactional - by the time a service method returns,
 * its transaction has committed, so publishing afterwards is effectively
 * AFTER_COMMIT (see BookingFacade for the full rationale).
 */
@Component
@RequiredArgsConstructor
public class InventoryFacade {

    private final FlightServiceClient flightServiceClient;
    private final InventoryService inventoryService;
    private final SeatReservationService seatReservationService;
    private final InventoryEventProducer inventoryEventProducer;

    public FlightInventoryResponse createInventory(CreateFlightInventoryRequest request) {

        FlightDetails flight = flightServiceClient.getFlight(request.flightId());

        if ("CANCELLED".equalsIgnoreCase(flight.status())) {
            throw new IllegalArgumentException("Cannot create inventory for a cancelled flight");
        }

        FlightInventoryResponse inventory = inventoryService.createInventory(request);

        inventoryEventProducer.publishInventoryCreated(inventory);

        return inventory;
    }

    public SeatHoldResponse holdSeat(HoldSeatRequest request) {

        SeatHoldResponse hold = inventoryService.holdSeat(request);

        inventoryEventProducer.publishSeatHeld(hold);

        return hold;
    }

    public SeatHoldResponse releaseHold(ReleaseSeatRequest request) {

        SeatHoldResponse hold = inventoryService.releaseHold(request);

        inventoryEventProducer.publishSeatReleased(hold);

        return hold;
    }

    public SeatReservationResponse reserveSeat(ReserveSeatRequest request) {

        SeatReservationResponse reservation = seatReservationService.reserveSeat(request);

        inventoryEventProducer.publishSeatReserved(reservation);

        return reservation;
    }

    public SeatReservationResponse cancelReservation(ReleaseSeatRequest request) {

        SeatReservationResponse reservation = seatReservationService.cancelReservation(request);

        inventoryEventProducer.publishReservationCancelled(reservation);

        return reservation;
    }
}
