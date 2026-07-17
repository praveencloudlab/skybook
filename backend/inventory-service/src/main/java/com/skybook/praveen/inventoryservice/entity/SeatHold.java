package com.skybook.praveen.inventoryservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.inventoryservice.enums.SeatAssignmentMode;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A time-boxed soft lock on one seat of one flight, taken while a booking is
 * in progress. Expiry is enforced by SeatHoldExpiryJob using expiresAt.
 *
 * "One ACTIVE hold per seat per flight" cannot be a DB unique constraint
 * (released/expired rows share the same seat), so it is enforced in the
 * service layer under FlightInventory's optimistic lock - every hold/release
 * bumps the inventory counts, so two racing holds collide on @Version.
 */
@Entity
@Table(name = "seat_holds", indexes = {
        @Index(name = "ix_seat_holds_status_expires", columnList = "status, expiresAt"),
        @Index(name = "ix_seat_holds_booking", columnList = "bookingId"),
        // Backs the money-idempotency lookup: the passenger's ACTIVE hold on a flight (§6).
        @Index(name = "ix_seat_holds_passenger",
                columnList = "flight_inventory_id, bookingPassengerId, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatHold extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_inventory_id", nullable = false)
    private FlightInventory flightInventory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aircraft_seat_id", nullable = false)
    private AircraftSeat aircraftSeat;

    // Reference only - id of the Booking in booking-service that requested
    // the hold. Correlation key for confirm/release calls.
    @Column(nullable = false, updatable = false)
    private Long bookingId;

    // ---- Immutable pricing snapshot (SEAT_SELECTION_MODULE.md §6, round 7) ----
    // All four are DB-nullable: inventory has no Flyway and seat_holds is
    // populated, so a pre-branch (legacy) hold has them null. Completeness is
    // enforced in the SERVICE for every NEW hold, not by the schema; a hold
    // with any of these null is by definition legacy and is never replayed.
    // updatable = false makes the snapshot immutable once written.

    // The booking passenger this hold belongs to - key for the money-idempotency
    // replay lookup. Legacy holds carry null and can never be matched by it.
    @Column(updatable = false)
    private Long bookingPassengerId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 10)
    private SeatAssignmentMode assignmentMode;

    // What the seat is worth by its attributes at hold time (policy result).
    @Column(updatable = false, precision = 19, scale = 2)
    private BigDecimal listedSurcharge;

    // What the passenger is actually charged: listed (MANUAL) or 0.00 (AUTO).
    @Column(updatable = false, precision = 19, scale = 2)
    private BigDecimal chargedSurcharge;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private SeatHoldStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime heldAt;

    // TTL boundary - computed by SeatHoldExpiryCalculator at creation.
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = SeatHoldStatus.ACTIVE;
        }
        if (heldAt == null) {
            heldAt = LocalDateTime.now();
        }
    }
}
