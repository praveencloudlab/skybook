package com.skybook.praveen.checkinservice.entity;

import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;
import com.skybook.praveen.common.entity.Auditable;
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
 * A point-in-time document, not a live view of CheckIn (design doc section
 * 3.2/5.6/6) - deliberately @ManyToOne rather than @OneToOne, because a seat
 * change after check-in revokes the current pass and inserts a new one
 * rather than mutating it in place, so a CheckIn can legitimately have
 * several REVOKED rows plus at most one ACTIVE one over its lifetime.
 * "At most one ACTIVE per CheckIn" is a service-layer guarantee (section
 * 3.1.1), not a DB constraint on checkInId.
 */
@Entity
@Table(name = "boarding_passes", indexes = {
        @Index(name = "ix_boarding_pass_checkin", columnList = "check_in_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoardingPass extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_in_id", nullable = false, updatable = false)
    private CheckIn checkIn;

    // "BP-2026-K7M4Z9" style - BoardingPassNumberGenerator.
    @Column(name = "boarding_pass_number", nullable = false, unique = true, updatable = false, length = 20)
    private String boardingPassNumber;

    // The signed value encoded into the QR (design doc section 6).
    @Column(nullable = false, unique = true, updatable = false, length = 255)
    private String token;

    // Denormalized so the pass is a self-contained printable/scannable
    // document even if the live CheckIn later changes for unrelated reasons.
    @Column(name = "passenger_name", nullable = false, updatable = false, length = 200)
    private String passengerName;

    @Column(name = "booking_reference", nullable = false, updatable = false, length = 10)
    private String bookingReference;

    @Column(name = "flight_number", length = 10, updatable = false)
    private String flightNumber;

    @Column(name = "origin_airport_code", length = 3, updatable = false)
    private String originAirportCode;

    @Column(name = "destination_airport_code", length = 3, updatable = false)
    private String destinationAirportCode;

    @Column(name = "seat_number", length = 5, updatable = false)
    private String seatNumber;

    @Column(length = 10)
    private String gate;

    @Column(name = "boarding_time")
    private LocalDateTime boardingTime;

    @Column(name = "boarding_group", length = 5)
    private String boardingGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BoardingPassStatus status;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    // Self-reference to the pass this one was replaced by on reissue -
    // nullable, set only on the old (REVOKED) row. Lets support trace "what
    // did this old QR turn into" (design doc section 15, risk 3).
    @Column(name = "reissued_as_id")
    private Long reissuedAsId;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = BoardingPassStatus.ACTIVE;
        }
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }
}
