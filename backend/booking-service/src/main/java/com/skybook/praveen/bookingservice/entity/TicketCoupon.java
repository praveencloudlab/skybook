package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.common.entity.Auditable;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One flight coupon (ROUND_TRIP_MODULE.md): 1:1 with the traveller's
 * per-segment BookingPassenger row, which is exactly "this passenger on this
 * direction". "Coupon 2 CANCELLED / Coupon 1 FLOWN" is first-class state.
 */
@Entity
@Table(name = "ticket_coupons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketCoupon extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_passenger_id", nullable = false, unique = true)
    private BookingPassenger bookingPassenger;

    // 1-based, in segment order: coupon 1 = outbound, coupon 2 = return.
    @Column(name = "coupon_number", nullable = false)
    private int couponNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CouponStatus status;
}
