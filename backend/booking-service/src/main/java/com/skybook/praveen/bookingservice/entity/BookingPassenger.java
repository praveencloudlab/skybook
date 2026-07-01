package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.common.entity.Auditable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;

/**
 * One traveler's line item within a Booking (docs section 3.2/9) - enables
 * multi-passenger PNRs from day one. travelClass/fareType/seatNumber/fare
 * live here rather than on Booking, since different passengers on the same
 * PNR can fly different classes (e.g. a mixed-class family booking).
 *
 * flightId is denormalized from Booking.flightId purely so the
 * (flightId, seatNumber) uniqueness constraint - the real backstop against
 * concurrently double-booking a seat - can live on this table without a
 * cross-table constraint.
 */
@Entity
@Table(
        name = "booking_passengers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_flight_seat",
                columnNames = {"flight_id", "seat_number"}
        )
)
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

    @Column(nullable = false, length = 20)
    private TravelClass travelClass;

    @Column(nullable = false, length = 20)
    private FareType fareType;

    @Column(name = "seat_number", nullable = false, length = 5)
    private String seatNumber;

    @Column(nullable = false)
    private BigDecimal fare;

    @Column(nullable = false, length = 20)
    private CheckInStatus checkInStatus;

    @PrePersist
    public void prePersist() {
        if (checkInStatus == null) {
            checkInStatus = CheckInStatus.NOT_OPEN;
        }
    }
}
