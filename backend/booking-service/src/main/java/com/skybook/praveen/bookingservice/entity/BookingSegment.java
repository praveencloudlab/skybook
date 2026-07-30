package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One flight leg of a booking's journey (ROUND_TRIP_MODULE.md §3): segment 0
 * is the outbound, 1 the return. flightId identifies a dated flight instance
 * (flights rows carry a concrete departure datetime, there is no route
 * entity), so no schedule snapshot is needed here - events enrich from
 * flight-service at publish time, exactly as single-flight bookings do.
 *
 * Deliberately thin: everything per-passenger-per-direction (seat, fare
 * breakdown, check-in mirror, cancellation) lives on BookingPassenger, whose
 * rows are per segment since V10.
 */
@Entity
@Table(name = "booking_segments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSegment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "segment_index", nullable = false)
    private int segmentIndex;

    // 0 = outbound, 1 = return. A same-carrier through-ticket's connection
    // legs are multiple segments of direction 0; the cancellation matrix and
    // per-direction baggage fees key on this, never on segment_index.
    @Column(nullable = false)
    private int direction;

    @Column(name = "flight_id", nullable = false)
    private Long flightId;
}
