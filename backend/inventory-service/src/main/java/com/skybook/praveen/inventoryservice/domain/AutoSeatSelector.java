package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the seat an AUTO assignment should hold (SEAT_SELECTION_MODULE.md
 * §5.2): the lowest-demand available seat in the passenger's cabin, ordered by
 * the design's deterministic tuple - NOT by price, so re-tuning surcharge
 * config never changes which seat auto-assign lands on:
 *
 *   1. exit rows are EXCLUDED entirely (eligibility rules deferred, §12 -
 *      never auto-seat a potentially ineligible passenger there)
 *   2. non-front-of-cabin before front-of-cabin
 *   3. MIDDLE before AISLE before WINDOW
 *   4. tie-break by (rowNumber, seatNumber) for determinism
 *
 * Exhaustion: if only exit-row seats remain, the pick is empty - the caller
 * raises the clear "no seat available" error rather than seating someone in a
 * row they may not legally occupy.
 *
 * Pure function of (candidate seats, cabin context) - no I/O. Candidates must
 * already be filtered to the target cabin and to genuinely available seats;
 * this class only orders and picks.
 */
@Component
public class AutoSeatSelector {

    private final SeatPricingPolicy pricingPolicy;

    public AutoSeatSelector(SeatPricingPolicy pricingPolicy) {
        this.pricingPolicy = pricingPolicy;
    }

    public Optional<AircraftSeat> pickPreferred(List<AircraftSeat> availableInCabin, CabinPricingContext cabin) {
        return availableInCabin.stream()
                .filter(seat -> !Boolean.TRUE.equals(seat.getExitRow()))
                .min(Comparator
                        .comparing((AircraftSeat seat) -> cabin.isFrontOfCabin(seat.getRowNumber()))
                        .thenComparingInt(seat -> positionRank(seat.getPosition()))
                        .thenComparingInt(AircraftSeat::getRowNumber)
                        .thenComparing(AircraftSeat::getSeatNumber));
    }

    /** MIDDLE (free pool) first, WINDOW (highest demand) last. */
    private int positionRank(SeatPosition position) {
        return switch (position) {
            case MIDDLE -> 0;
            case AISLE -> 1;
            case WINDOW -> 2;
        };
    }

    /** Convenience for callers that only need the listed surcharge of the pick. */
    public BigDecimal listedSurchargeOf(AircraftSeat seat, CabinPricingContext cabin) {
        return pricingPolicy.calculateListedSurcharge(seat, cabin);
    }
}
