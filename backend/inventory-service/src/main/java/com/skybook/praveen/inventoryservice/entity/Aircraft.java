package com.skybook.praveen.inventoryservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A physical airframe in the fleet. Seat maps (AircraftSeat) and per-flight
 * sellable counts (FlightInventory) hang off this; flights in flight-service
 * are referenced by id only, consistent with how booking-service references
 * flights.
 */
@Entity
@Table(name = "aircraft")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Aircraft extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Registration / tail number, e.g. "VT-SKB" - the airline-wide unique
    // identifier for a physical airframe.
    @Column(nullable = false, unique = true, updatable = false, length = 10)
    private String registrationNumber;

    @Column(nullable = false, length = 50)
    private String manufacturer;

    // e.g. "A320neo", "737 MAX 8"
    @Column(nullable = false, length = 50)
    private String model;

    // Denormalized total - kept in sync with the AircraftSeat rows by the
    // service layer, never set independently by callers.
    @Column(nullable = false)
    private Integer totalSeats;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AircraftStatus status;

    // The seat map. Owned by this aggregate - seats are created/removed via
    // cascade, never saved independently (same pattern as Booking.passengers).
    @OneToMany(mappedBy = "aircraft", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("rowNumber ASC, seatNumber ASC")
    @Builder.Default
    private List<AircraftSeat> seats = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = AircraftStatus.ACTIVE;
        }
        if (totalSeats == null) {
            totalSeats = 0;
        }
    }
}
