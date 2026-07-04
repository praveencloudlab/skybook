package com.skybook.praveen.inventoryservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Append-only audit trail for one flight's inventory (same role as
 * BookingHistory in booking-service). Rows are written by the service layer
 * in the same transaction as the change they record - never updated, never
 * deleted.
 */
@Entity
@Table(name = "inventory_history", indexes = {
        @Index(name = "ix_inventory_history_type", columnList = "historyType")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryHistory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_inventory_id", nullable = false)
    private FlightInventory flightInventory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private InventoryHistoryType historyType;

    // Which seat the change touched - null for inventory-level events
    // (created/closed/reopened/sold-out).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aircraft_seat_id")
    private AircraftSeat aircraftSeat;

    // Reference only - the booking that triggered the change, if any.
    @Column(updatable = false)
    private Long bookingId;

    // Free-text context, e.g. "hold expired after 15m" - never parsed.
    @Column(length = 500)
    private String details;

    @Column(nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    public void prePersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
