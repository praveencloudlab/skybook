package com.skybook.praveen.inventoryservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Sellable-seat state for one flight - the aggregate root that SeatHold and
 * SeatReservation hang off. The flight itself lives in flight-service and is
 * referenced by id only (same convention as Booking.flightId).
 *
 * Counts are denormalized snapshots maintained by the service layer inside
 * the same transaction as the hold/reservation rows; invariant:
 * availableSeats + heldSeats + reservedSeats + blockedSeats == totalSeats.
 * Concurrent updates are guarded by Auditable's @Version (optimistic locking).
 */
@Entity
@Table(name = "flight_inventory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightInventory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Reference only - id of the Flight in flight-service. One inventory
    // record per flight.
    @Column(nullable = false, unique = true, updatable = false)
    private Long flightId;

    // The airframe operating this flight - source of the seat map.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aircraft_id", nullable = false)
    private Aircraft aircraft;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InventoryStatus status;

    // Sellable seats on this flight (aircraft seats that are ACTIVE, minus
    // any per-flight blocks). Fixed at inventory creation.
    @Column(nullable = false)
    private Integer totalSeats;

    @Column(nullable = false)
    private Integer availableSeats;

    @Column(nullable = false)
    private Integer heldSeats;

    @Column(nullable = false)
    private Integer reservedSeats;

    // Per-flight operational blocks (e.g. crew seat on this leg only).
    @Column(nullable = false)
    private Integer blockedSeats;

    // Append-only audit trail, written via cascade in the same transaction
    // as each change (same pattern as Booking.history).
    @OneToMany(mappedBy = "flightInventory", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("changedAt ASC")
    @Builder.Default
    private List<InventoryHistory> history = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = InventoryStatus.OPEN;
        }
        if (totalSeats == null) {
            totalSeats = 0;
        }
        if (availableSeats == null) {
            availableSeats = totalSeats - (blockedSeats == null ? 0 : blockedSeats);
        }
        if (heldSeats == null) {
            heldSeats = 0;
        }
        if (reservedSeats == null) {
            reservedSeats = 0;
        }
        if (blockedSeats == null) {
            blockedSeats = 0;
        }
    }
}
