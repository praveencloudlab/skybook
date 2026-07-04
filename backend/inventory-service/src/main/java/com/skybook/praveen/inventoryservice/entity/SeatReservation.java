package com.skybook.praveen.inventoryservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A committed seat assignment - the hard counterpart of SeatHold. Normally
 * created by confirming an ACTIVE hold (originatingHold links back for
 * audit); direct reservations without a hold are also allowed (e.g. airport
 * counter flows).
 *
 * Like holds, "one RESERVED row per seat per flight" is enforced in the
 * service layer under FlightInventory's optimistic lock, not by a DB
 * constraint, because CANCELLED rows share the same seat.
 */
@Entity
@Table(name = "seat_reservations", indexes = {
        @Index(name = "ix_seat_reservations_booking", columnList = "bookingId"),
        @Index(name = "ix_seat_reservations_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatReservation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_inventory_id", nullable = false)
    private FlightInventory flightInventory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aircraft_seat_id", nullable = false)
    private AircraftSeat aircraftSeat;

    // Reference only - the Booking in booking-service this seat belongs to.
    @Column(nullable = false, updatable = false)
    private Long bookingId;

    // Reference only - the BookingPassenger sitting in this seat. Nullable:
    // a reservation can exist before seats are assigned to named passengers.
    @Column
    private Long bookingPassengerId;

    // The hold this reservation was confirmed from - null for direct
    // (hold-less) reservations. Audit link only; the hold row keeps its own
    // CONFIRMED status.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_hold_id")
    private SeatHold originatingHold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private SeatReservationStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime reservedAt;

    // Set exactly once, when status moves to CANCELLED.
    @Column
    private LocalDateTime cancelledAt;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = SeatReservationStatus.RESERVED;
        }
        if (reservedAt == null) {
            reservedAt = LocalDateTime.now();
        }
    }
}
