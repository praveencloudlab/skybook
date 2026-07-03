package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Published by inventory-service on the skybook.inventory.events topic.
 * Consumed later by booking-service (seat confirmation flows) and
 * potentially notification-service.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryEvent {

    /**
     * Event Type
     */
    private InventoryEventType type;

    /**
     * Flight the inventory belongs to (flight-service id)
     */
    private Long flightId;

    /**
     * Seat involved, e.g. "12A" - null for inventory-level events
     */
    private String seatNumber;

    /**
     * Booking that triggered the change (booking-service id) - null for
     * inventory-level events
     */
    private Long bookingId;

    /**
     * Free-text context
     */
    private String details;
}
