package com.skybook.praveen.checkinservice.entity;

import com.skybook.praveen.checkinservice.enums.CheckInStatus;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The CheckIn aggregate root (design doc section 3.1) - one row per
 * passenger per flight, not per booking (two travelers on the same PNR can
 * be in different check-in states). Everything booking/flight-related here
 * is a reference or a snapshot taken from BookingEvent at creation time;
 * booking-service and flight-service remain the sources of truth.
 *
 * Aggregate invariants (section 3.1.1) and where they are enforced:
 * - one CheckIn per bookingPassengerId    (DB unique constraint)
 * - window timing                          (CheckInValidator, against departureTime)
 * - at most one ACTIVE BoardingPass        (service layer, see BoardingPass)
 * - terminal states have no outgoing edges (CheckInStateMachine)
 */
@Entity
@Table(name = "check_ins", uniqueConstraints = @UniqueConstraint(
        name = "uk_checkin_booking_passenger",
        columnNames = "booking_passenger_id"
))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckIn extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Reference only - the Booking in booking-service.
    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    // PNR, denormalized for display without a booking-service call.
    @Column(name = "booking_reference", nullable = false, updatable = false, length = 10)
    private String bookingReference;

    // The passenger's line-item id in booking-service (BookingPassenger.id) -
    // NOT Passenger.id. Natural key: unique, makes the BookingEvent CONFIRMED
    // consumer idempotent on replay.
    @Column(name = "booking_passenger_id", nullable = false, updatable = false)
    private Long bookingPassengerId;

    // Flight context, snapshotted from BookingEvent at creation (design doc
    // section 3.1) - live flight-service calls still happen at the moments
    // that matter (window open, boarding), this is for display only.
    @Column(name = "flight_id", nullable = false, updatable = false)
    private Long flightId;

    @Column(name = "flight_number", length = 10, updatable = false)
    private String flightNumber;

    @Column(name = "origin_airport_code", length = 3, updatable = false)
    private String originAirportCode;

    @Column(name = "destination_airport_code", length = 3, updatable = false)
    private String destinationAirportCode;

    @Column(name = "departure_time", updatable = false)
    private LocalDateTime departureTime;

    // Passenger snapshot from BookingEventPassenger.
    @Column(name = "passenger_name", nullable = false, updatable = false, length = 200)
    private String passengerName;

    // Snapshotted from BookingEvent.contactEmail at creation - lets
    // CheckInEventProducer publish it on CheckInEvent so notification-service
    // can email the boarding pass without a synchronous booking-service call.
    @Column(name = "contact_email", length = 200, updatable = false)
    private String contactEmail;

    @Column(name = "seat_number", length = 5)
    private String seatNumber;

    @Column(name = "travel_class", length = 20, updatable = false)
    private String travelClass;

    @Column(name = "fare_type", length = 20, updatable = false)
    private String fareType;

    // Free-seat-change entitlement (SEAT_SELECTION_MODULE.md §9): the seat
    // surcharge the passenger actually PAID at booking, snapshotted from the
    // CONFIRMED BookingEvent. Ceiling for check-in seat changes - a target
    // seat listing above it is rejected (downgrades forfeit the difference,
    // v1 policy). Nullable: legacy events don't carry it => treated as 0
    // (only free seats reachable at check-in).
    @Column(name = "seat_surcharge_entitlement", updatable = false, precision = 19, scale = 2)
    private BigDecimal seatSurchargeEntitlement;

    /** ISO-4217 of the entitlement ("USD" v1). Nullable on legacy rows. */
    @Column(name = "entitlement_currency", length = 3, updatable = false)
    private String entitlementCurrency;

    // Ownership (SECURITY_HARDENING_MODULE.md §4.2): the booking owner's JWT
    // subject, snapshotted from the CONFIRMED event. Nullable/immutable; legacy
    // rows are null and reachable only by ADMIN/SERVICE.
    @Column(name = "owner_subject", updatable = false)
    private String ownerSubject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private CheckInStatus status;

    // True when the snapshotted passenger data included document fields at
    // CheckIn creation (design doc section 5.2) - presence only, v1 does not
    // validate document authenticity.
    @Column(name = "document_verified", nullable = false)
    private boolean documentVerified;

    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    @Column(name = "boarded_at")
    private LocalDateTime boardedAt;

    // Set via PATCH /api/checkins/{id}/gate - not synced with any real
    // airport gate-management system in v1.
    @Column(length = 10)
    private String gate;

    @Column(name = "boarding_group", length = 5)
    private String boardingGroup;

    // Boarding passes are saved explicitly by the service layer, not
    // cascaded - a CheckIn can accumulate multiple REVOKED rows over its
    // lifetime (seat changes), so this mapping exists for reads only, same
    // pattern as Payment.transactions/refunds.
    @OneToMany(mappedBy = "checkIn", fetch = FetchType.LAZY)
    @OrderBy("issuedAt ASC")
    @Builder.Default
    private List<BoardingPass> boardingPasses = new ArrayList<>();

    @OneToMany(mappedBy = "checkIn", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Baggage> baggage = new ArrayList<>();

    // Audit trail, written via cascade in the same transaction as each
    // change - same pattern as Payment.history/Booking.history.
    @OneToMany(mappedBy = "checkIn", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("changedAt ASC")
    @Builder.Default
    private List<CheckInHistory> history = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = CheckInStatus.NOT_OPEN;
        }
    }
}
