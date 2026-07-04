package com.skybook.praveen.inventoryservice.producer;

import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.InventoryEvent;
import com.skybook.praveen.common.event.InventoryEventType;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes InventoryEvent to Kafka. Called by InventoryFacade AFTER the
 * service's @Transactional method has returned (and committed) - same
 * after-commit reasoning as BookingEventProducer/BookingFacade.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private final KafkaTemplate<String, InventoryEvent> kafkaTemplate;

    public void publishInventoryCreated(FlightInventoryResponse inventory) {
        publish(InventoryEventType.INVENTORY_CREATED, inventory.flightId(), null, null,
                "Inventory created with " + inventory.totalSeats() + " seats on aircraft "
                        + inventory.aircraftRegistrationNumber());
    }

    public void publishSeatHeld(SeatHoldResponse hold) {
        publish(InventoryEventType.SEAT_HELD, hold.flightId(), hold.seatNumber(), hold.bookingId(),
                "Hold expires at " + hold.expiresAt());
    }

    public void publishSeatReleased(SeatHoldResponse hold) {
        publish(InventoryEventType.SEAT_RELEASED, hold.flightId(), hold.seatNumber(), hold.bookingId(), null);
    }

    public void publishSeatReserved(SeatReservationResponse reservation) {
        publish(InventoryEventType.SEAT_RESERVED, reservation.flightId(), reservation.seatNumber(),
                reservation.bookingId(), null);
    }

    public void publishReservationCancelled(SeatReservationResponse reservation) {
        publish(InventoryEventType.RESERVATION_CANCELLED, reservation.flightId(), reservation.seatNumber(),
                reservation.bookingId(), null);
    }

    private void publish(InventoryEventType type, Long flightId, String seatNumber, Long bookingId, String details) {

        InventoryEvent event = InventoryEvent.builder()
                .type(type)
                .flightId(flightId)
                .seatNumber(seatNumber)
                .bookingId(bookingId)
                .details(details)
                .build();

        kafkaTemplate.send(KafkaTopics.INVENTORY_EVENTS, event);

        log.info("Published {} event for flight {} seat {}", type, flightId, seatNumber);
    }
}
