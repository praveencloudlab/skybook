package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.BookingStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
 * The Booking aggregate root (docs/BOOKING_SERVICE_MODULE.md section 3).
 * Everything below - passengers, contact, payment, history - is persisted in
 * one transaction via this root; other aggregates (Flight, the Customer/User
 * living in auth-service) are referenced by id only.
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Immutable, system-generated, e.g. "SB8KF7" - see PnrGenerator.
    @Column(nullable = false, unique = true, updatable = false, length = 10)
    private String bookingReference;

    // Reference only - the id of the User in auth-service. No local
    // Customer table (docs section 3.7).
    //
    // OPTIONAL since V6 (FRONTEND_MODULE.md §10.3): ownership is carried by
    // ownerSubject, and nothing authorizes or looks up by this. It was NOT NULL,
    // which forced every client to invent a meaningless number.
    @Column
    private Long customerId;

    // DEPRECATED (ROUND_TRIP_MODULE.md §3): segment 0's flight, kept one
    // release for readers not yet on segments. The authoritative journey is
    // the segments list below; a follow-up migration drops this column.
    @Column(nullable = false)
    private Long flightId;

    // The journey's flight legs in order (0 = outbound, 1 = return). A
    // single-flight booking is simply a one-segment journey.
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("segmentIndex ASC")
    @Builder.Default
    private List<BookingSegment> segments = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus bookingStatus;

    @Column(nullable = false)
    private LocalDateTime bookingDate;

    // Derived - sum of BookingPassenger.fare. Never set independently;
    // recomputed by BookingServiceImpl whenever passengers change.
    @Column(nullable = false)
    private BigDecimal totalFare;

    @Column(length = 500)
    private String remarks;

    // Ownership (SECURITY_HARDENING_MODULE.md §4.2): the authenticated JWT
    // subject captured at booking creation. Immutable; a USER may act only on
    // bookings whose ownerSubject equals their token subject. Legacy rows are
    // null and reachable only by ADMIN/SERVICE.
    @Column(name = "owner_subject", updatable = false)
    private String ownerSubject;

    // id ASC = insertion order = segment-major (ROUND_TRIP_MODULE.md §5):
    // the facade's row<->request-detail correlation relies on a reloaded
    // booking listing rows in exactly the order the draft created them.
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @Builder.Default
    private List<BookingPassenger> passengers = new ArrayList<>();

    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private BookingContact contact;

    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private BookingPayment payment;

    // Append-only audit trail, populated by BookingStateMachine - see that
    // class for why this is populated via cascade rather than a separate save.
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("changedAt ASC")
    @Builder.Default
    private List<BookingHistory> history = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (bookingStatus == null) {
            bookingStatus = BookingStatus.CREATED;
        }
        if (bookingDate == null) {
            bookingDate = LocalDateTime.now();
        }
        if (totalFare == null) {
            totalFare = BigDecimal.ZERO;
        }
    }
}
