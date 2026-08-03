package com.skybook.praveen.bookingservice.scheduler;

import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.entity.TicketCoupon;
import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.bookingservice.repository.TicketCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks used coupons FLOWN once their flight has departed
 * (ROUND_TRIP_MODULE.md, tickets & coupons): a CHECKED_IN coupon whose
 * segment flight left is history at the record level, not just in the UI's
 * derivation. OPEN coupons are deliberately left alone - not presenting for
 * a leg is a no-show, and inventing FLOWN for it would be false.
 *
 * <p>Departure times come from flight-service with the service token (no
 * user context on a scheduler thread), one lookup per DISTINCT flight, and
 * a flight that can't be fetched is simply skipped until the next run -
 * the sweep must never fail a batch over one degraded lookup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlownCouponSweepJob {

    private final TicketCouponRepository ticketCouponRepository;
    private final FlightServiceClient flightServiceClient;

    @Scheduled(fixedDelayString = "${booking.coupons.flown-sweep-interval-ms:3600000}", initialDelay = 90000)
    @Transactional
    public void sweepFlownCoupons() {

        List<TicketCoupon> candidates = ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN);
        if (candidates.isEmpty()) {
            return;
        }

        Map<Long, FlightDetails> flightById = new HashMap<>();
        int flown = 0;

        for (TicketCoupon coupon : candidates) {
            Long flightId = coupon.getBookingPassenger().getFlightId();
            FlightDetails flight = flightById.computeIfAbsent(flightId, id -> {
                try {
                    return flightServiceClient.getFlightAsService(id);
                } catch (RuntimeException e) {
                    log.warn("FLOWN sweep could not fetch flight {}: {}", id, e.getMessage());
                    return null;
                }
            });
            // Departure is airport-local: "has it flown" is judged by that
            // airport's clock, not the server's (AirportTimeZones).
            if (flight != null && flight.departureTime() != null && flight.departureTime().isBefore(
                    com.skybook.praveen.common.time.AirportTimeZones.nowAt(flight.originAirportCode()))) {
                coupon.setStatus(CouponStatus.FLOWN);
                flown++;
            }
        }

        if (flown > 0) {
            ticketCouponRepository.saveAll(candidates);
            log.info("FlownCouponSweepJob marked {} coupon(s) FLOWN across {} flight(s)",
                    flown, flightById.size());
        }
    }
}
