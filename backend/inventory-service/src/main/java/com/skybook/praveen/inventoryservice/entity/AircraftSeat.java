package com.skybook.praveen.inventoryservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One physical seat on one airframe - the seat-map building block. Seat
 * numbers repeat across aircraft ("12A" exists on every A320), so uniqueness
 * is per-aircraft, enforced by the composite constraint below.
 */
@Entity
@Table(name = "aircraft_seats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_aircraft_seat_number",
                columnNames = {"aircraft_id", "seatNumber"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AircraftSeat extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aircraft_id", nullable = false)
    private Aircraft aircraft;

    // e.g. "12A" - row + letter, unique within the aircraft.
    @Column(nullable = false, length = 5)
    private String seatNumber;

    // Denormalized from seatNumber for cheap row-based queries/sorting.
    @Column(nullable = false)
    private Integer rowNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatType seatType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatPosition position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private AircraftSeatStatus status;

    // Exit-row seats carry allocation restrictions (no minors/infants) -
    // enforced later by SeatAllocationValidator, stored here as fact.
    @Column(nullable = false)
    private Boolean exitRow;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = AircraftSeatStatus.ACTIVE;
        }
        if (exitRow == null) {
            exitRow = Boolean.FALSE;
        }
    }
}
