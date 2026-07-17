package com.skybook.praveen.checkinservice.client;

import com.skybook.praveen.checkinservice.exception.InventoryServiceUnavailableException;
import com.skybook.praveen.checkinservice.exception.SeatUnavailableException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

import java.util.List;
import java.util.Optional;

/**
 * Seat-reservation boundary for checkin-service (design doc section 9.1):
 * confirm a passenger's seat is RESERVED before allowing check-in, and move
 * a reservation on seat change (reserve the new seat, then cancel the old
 * one - no hold step, inventory-service reserves directly from AVAILABLE
 * when no hold exists, same as booking-service's "reserveDirect" path).
 *
 * Domain boundary of the two-bean resilience split (RESILIENCE_MODULE.md
 * §5): CallNotPermittedException (open circuit) and BulkheadFullException
 * (saturation) translate to the same InventoryServiceUnavailableException
 * callers already handle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryServiceClient {

    private static final String NO_INVENTORY_MARKER = "Flight inventory not found";

    private final ResilientInventoryClient resilientClient;

    /**
     * Reads propagate unavailability as the domain exception (they gate
     * check-in eligibility - failing open would let passengers check in
     * against unverified seats); an empty list still means "nothing to
     * validate against," same graceful-degradation policy as before.
     */
    public List<SeatReservationDetails> getReservationsForBooking(Long bookingId) {
        try {
            return resilientClient.getReservationsByBooking(bookingId);

        } catch (CallNotPermittedException | BulkheadFullException fastFail) {
            log.warn("inventory-service reservations lookup rejected without attempt ({}), booking {}",
                    fastFail.getClass().getSimpleName(), bookingId);
            throw new InventoryServiceUnavailableException(bookingId, fastFail);

        } catch (FeignException unreachable) {
            log.error("Could not reach inventory-service for reservations of booking {}", bookingId, unreachable);
            throw new InventoryServiceUnavailableException(bookingId, unreachable);
        }
    }

    /**
     * Direct check-in reservation (§9): inventory authoritatively enforces
     * cabin match + listedSurcharge <= maxAllowedSurcharge under the flight
     * lock. Optional.empty() = the flight has no inventory record - proceed
     * without reserving.
     */
    public Optional<SeatReservationDetails> reserveSeat(Long flightId, String seatNumber,
                                                         Long bookingId, Long bookingPassengerId,
                                                         String travelClass, BigDecimal maxAllowedSurcharge) {
        try {
            return Optional.of(resilientClient.reserveSeat(InventorySeatCall.reserve(
                    flightId, seatNumber, bookingId, bookingPassengerId, travelClass, maxAllowedSurcharge)));

        } catch (FeignException.NotFound notFound) {
            if (notFound.contentUTF8().contains(NO_INVENTORY_MARKER)) {
                return Optional.empty();
            }
            throw new SeatUnavailableException(flightId, seatNumber,
                    "seat does not exist in the flight's seat inventory");

        } catch (FeignException.Conflict conflict) {
            // Occupied seat, cross-cabin move, or above-entitlement seat -
            // inventory's message names the exact rule (§9).
            String body = conflict.contentUTF8();
            throw new SeatUnavailableException(flightId, seatNumber,
                    body == null || body.isBlank() ? "already held or reserved" : body);

        } catch (CallNotPermittedException | BulkheadFullException fastFail) {
            log.warn("inventory-service reserve rejected without attempt ({}), seat {} flight {}",
                    fastFail.getClass().getSimpleName(), seatNumber, flightId);
            throw new InventoryServiceUnavailableException(flightId, fastFail);

        } catch (FeignException unreachable) {
            log.error("Could not reach inventory-service to reserve seat {} on flight {}",
                    seatNumber, flightId, unreachable);
            throw new InventoryServiceUnavailableException(flightId, unreachable);
        }
    }

    /** Compensation/cleanup for the old seat on a successful seat change - never throws. */
    public void cancelReservationQuietly(Long flightId, String seatNumber, Long bookingId, String reason) {
        try {
            resilientClient.cancelReservation(InventorySeatCall.cancel(flightId, seatNumber, bookingId, reason));
            log.info("Cancelled reservation of seat {} flight {} ({})", seatNumber, flightId, reason);
        } catch (FeignException | CallNotPermittedException | BulkheadFullException e) {
            log.warn("Could not cancel reservation of seat {} flight {} ({}): {}",
                    seatNumber, flightId, reason, e.getMessage());
        }
    }
}
