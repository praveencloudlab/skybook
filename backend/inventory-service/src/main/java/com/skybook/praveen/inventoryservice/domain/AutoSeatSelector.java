package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the seat an AUTO assignment should hold (SEAT_SELECTION_MODULE.md §5.2):
 * the LOWEST-demand available seat in the passenger's cabin, so that premium
 * seats stay in the pool for passengers who'll pay for them. "Low demand" is
 * defined by listed surcharge - the cheapest tier first (standard middle = 0) -
 * with a deterministic tuple tiebreak (row, then seat number) so the same
 * inventory always yields the same pick and the concurrency tests are stable.
 *
 * Pure function of (candidate seats, cabin context, pricing policy) - no I/O.
 * Candidates must already be filtered to the target cabin and to genuinely
 * available seats; this class only orders and picks.
 */
@Component
public class AutoSeatSelector {

    private final SeatPricingPolicy pricingPolicy;

    public AutoSeatSelector(SeatPricingPolicy pricingPolicy) {
        this.pricingPolicy = pricingPolicy;
    }

    public Optional<AircraftSeat> pickPreferred(List<AircraftSeat> availableInCabin, CabinPricingContext cabin) {
        return availableInCabin.stream().min(Comparator
                .comparing((AircraftSeat seat) -> pricingPolicy.calculateListedSurcharge(seat, cabin))
                .thenComparing(AircraftSeat::getRowNumber)
                .thenComparing(AircraftSeat::getSeatNumber));
    }

    /** Convenience for callers that only need the listed surcharge of the pick. */
    public BigDecimal listedSurchargeOf(AircraftSeat seat, CabinPricingContext cabin) {
        return pricingPolicy.calculateListedSurcharge(seat, cabin);
    }
}
