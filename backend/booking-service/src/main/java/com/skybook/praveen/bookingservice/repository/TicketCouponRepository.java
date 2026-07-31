package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.TicketCoupon;
import com.skybook.praveen.bookingservice.enums.CouponStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TicketCouponRepository extends JpaRepository<TicketCoupon, Long> {

    /**
     * Coupons the FLOWN sweep considers (ROUND_TRIP_MODULE.md, tickets &
     * coupons): a CHECKED_IN coupon whose flight has departed was used - the
     * passenger presented for that leg. OPEN coupons stay OPEN (an unused
     * coupon is a no-show fact, not a flown one); terminal states never move.
     */
    @Query("SELECT c FROM TicketCoupon c JOIN FETCH c.bookingPassenger bp WHERE c.status = :status")
    List<TicketCoupon> findByStatus(CouponStatus status);
}
