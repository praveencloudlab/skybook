package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.common.entity.Auditable;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One traveler's line item within a Booking (docs section 3.2/9) - enables
 * multi-passenger PNRs from day one. travelClass/fareType/seatNumber/fare
 * live here rather than on Booking, since different passengers on the same
 * PNR can fly different classes (e.g. a mixed-class family booking).
 *
 * The old uk_flight_seat unique constraint is GONE (SEAT_SELECTION_MODULE.md
 * §2.6, V4): being unconditional it blocked cancel -> rebook-same-seat, since
 * a cancelled booking keeps its historical seat row. Live seat exclusivity is
 * inventory-service's job (shared flight lock + active holds/reservations);
 * this table keeps seat/fare rows as historical snapshot and audit only.
 */
@Entity
@Table(name = "booking_passengers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingPassenger extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    // Cascades PERSIST/MERGE - v1 creates a fresh Passenger row per booking
    // (docs section 3.6), so it's saved transitively through the Booking
    // aggregate root rather than needing a separate explicit save.
    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @Column(name = "flight_id", nullable = false)
    private Long flightId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TravelClass travelClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FareType fareType;

    // Nullable: a DRAFT booking commits before any seat exists (§5.1); the
    // finalize step writes the seat inventory actually held. Stays null on a
    // finalized booking only when the flight has no seat inventory at all.
    @Column(name = "seat_number", length = 5)
    private String seatNumber;

    // Immutable fare breakdown (SEAT_SELECTION_MODULE.md §8). Persisted, never
    // recomputed from current config: refunds, invoices and check-in seat-change
    // comparisons read these, so an old booking always shows what it actually
    // charged. fare (below) stays the all-in total = baseFare + seatSurcharge.

    /** Cabin base fare at booking time (FareCalculator output). */
    @Column(name = "base_fare", nullable = false)
    private BigDecimal baseFare;

    /** The surcharge actually CHARGED: 0 for an AUTO seat, the seat's listed surcharge for a MANUAL one. */
    @Column(name = "seat_surcharge", nullable = false)
    private BigDecimal seatSurcharge;

    @Enumerated(EnumType.STRING)
    @Column(name = "charged_seat_assignment_mode", nullable = false, length = 10)
    private SeatAssignmentMode chargedSeatAssignmentMode;

    /** ISO-4217 of baseFare/seatSurcharge/fare - aligned with the booking's payment currency. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** All-in total = baseFare + seatSurcharge. What payment/refund/invoice bill against (unchanged). */
    @Column(nullable = false)
    private BigDecimal fare;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckInStatus checkInStatus;

    @PrePersist
    public void prePersist() {
        if (checkInStatus == null) {
            checkInStatus = CheckInStatus.NOT_OPEN;
        }
        // Self-backfilling defaults so any code path (or a pre-breakdown row
        // being re-persisted) stays consistent: no surcharge, manual, base
        // equals the total. New bookings set these explicitly.
        if (seatSurcharge == null) {
            seatSurcharge = BigDecimal.ZERO;
        }
        if (chargedSeatAssignmentMode == null) {
            chargedSeatAssignmentMode = SeatAssignmentMode.MANUAL;
        }
        if (baseFare == null) {
            baseFare = fare;
        }
        if (currency == null) {
            currency = "USD";
        }
    }
}
