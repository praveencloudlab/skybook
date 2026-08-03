package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.TicketStatus;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The IATA-style e-ticket (ROUND_TRIP_MODULE.md, tickets & coupons): ONE per
 * (booking x traveller), covering their WHOLE journey, with one coupon per
 * segment. Issued when the booking reaches CONFIRMED. The 13-digit number is
 * derived deterministically from booking id + traveller index, so a
 * redelivered confirmation event re-derives the same numbers instead of
 * minting new ones.
 */
@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    // The traveller this ticket belongs to - their identity row, shared by
    // their per-segment BookingPassenger rows.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    // 13 digits, "125" accounting-code prefix (displayed 125-XXXXXXXXXX).
    @Column(name = "ticket_number", nullable = false, unique = true, updatable = false, length = 16)
    private String ticketNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketStatus status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("couponNumber ASC")
    @Builder.Default
    private List<TicketCoupon> coupons = new ArrayList<>();
}
