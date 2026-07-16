package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.InventoryServiceUnavailableException;
import com.skybook.praveen.bookingservice.exception.SeatUnavailableException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sprint 6 seat-inventory boundary. Encodes the "hold if inventory exists"
 * policy (design decision): a flight without a FlightInventory record is
 * legal - holds are skipped, signaled by Optional.empty(). A flight WITH
 * inventory enforces seat availability - conflicts become 409s.
 *
 * Domain boundary of the two-bean resilience split (RESILIENCE_MODULE.md
 * §5): delegates to ResilientInventoryClient (aspects against raw Feign
 * exceptions) and additionally translates CallNotPermittedException (open
 * circuit) and BulkheadFullException (saturation) into the same
 * InventoryServiceUnavailableException callers already handle.
 *
 * The *Quietly variants are for compensation/cleanup paths where the
 * primary operation (cancellation, payment-driven confirmation) must not
 * fail because inventory cleanup hiccupped - failures are logged, never
 * thrown; an open circuit there just logs like any other failure (the
 * SeatHoldExpiryJob sweep remains the self-healing backstop).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryServiceClient {

    private static final String NO_INVENTORY_MARKER = "Flight inventory not found";

    private final ResilientInventoryClient resilientClient;

    /** Optional.empty() = the flight has no inventory record - proceed without a hold. */
    public Optional<InventoryHoldDetails> holdSeat(Long flightId, String seatNumber, Long bookingId,
                                                   Long bookingPassengerId, TravelClass travelClass) {
        try {
            return Optional.of(resilientClient.holdSeat(
                    InventorySeatCall.hold(flightId, seatNumber, bookingId, bookingPassengerId, travelClass)));

        } catch (FeignException.NotFound notFound) {
            if (notFound.contentUTF8().contains(NO_INVENTORY_MARKER)) {
                log.info("Flight {} has no seat inventory - booking proceeds without holds", flightId);
                return Optional.empty();
            }
            // Inventory exists but the seat doesn't (seat map mismatch) -
            // that seat cannot be sold.
            throw new SeatUnavailableException(flightId, seatNumber,
                    "seat does not exist in the flight's seat inventory");

        } catch (FeignException.Conflict conflict) {
            // Cabin mismatch, occupied seat, or a conflicting existing hold -
            // inventory's message names the exact rule.
            throw new SeatUnavailableException(flightId, seatNumber, conflictReason(conflict));

        } catch (CallNotPermittedException | BulkheadFullException fastFail) {
            log.warn("inventory-service hold rejected without attempt ({}), seat {} flight {}",
                    fastFail.getClass().getSimpleName(), seatNumber, flightId);
            throw new InventoryServiceUnavailableException(flightId, fastFail);

        } catch (FeignException unreachable) {
            log.error("Could not reach inventory-service to hold seat {} on flight {}",
                    seatNumber, flightId, unreachable);
            throw new InventoryServiceUnavailableException(flightId, unreachable);
        }
    }

    /**
     * Atomic auto-hold (SEAT_SELECTION_MODULE.md §5.2): inventory picks and
     * holds a low-demand seat in the passenger's cabin - always charged 0.00.
     * Optional.empty() = the flight has no inventory record (same hold-if-exists
     * policy as the manual path).
     */
    public Optional<InventoryHoldDetails> autoHoldSeat(Long flightId, Long bookingId,
                                                       Long bookingPassengerId, TravelClass travelClass) {
        try {
            return Optional.of(resilientClient.autoHoldSeat(flightId,
                    InventorySeatCall.autoHold(bookingId, bookingPassengerId, travelClass)));

        } catch (FeignException.NotFound notFound) {
            if (notFound.contentUTF8().contains(NO_INVENTORY_MARKER)) {
                log.info("Flight {} has no seat inventory - booking proceeds without holds", flightId);
                return Optional.empty();
            }
            throw new SeatUnavailableException(flightId, "(auto)",
                    "auto-assignment failed: " + notFound.contentUTF8());

        } catch (FeignException.Conflict conflict) {
            // Cabin exhausted / no such cabin on this aircraft / mode conflict.
            throw new SeatUnavailableException(flightId, "(auto)", conflictReason(conflict));

        } catch (CallNotPermittedException | BulkheadFullException fastFail) {
            log.warn("inventory-service auto-hold rejected without attempt ({}), flight {}",
                    fastFail.getClass().getSimpleName(), flightId);
            throw new InventoryServiceUnavailableException(flightId, fastFail);

        } catch (FeignException unreachable) {
            log.error("Could not reach inventory-service to auto-hold a seat on flight {}",
                    flightId, unreachable);
            throw new InventoryServiceUnavailableException(flightId, unreachable);
        }
    }

    /** Surfaces inventory's own 409 message (it names the violated rule) with a safe fallback. */
    private String conflictReason(FeignException.Conflict conflict) {
        String body = conflict.contentUTF8();
        return body == null || body.isBlank() ? "already held or reserved" : body;
    }

    public Optional<InventoryReservationDetails> reserveSeat(Long flightId, String seatNumber,
                                                             Long bookingId, Long bookingPassengerId) {
        try {
            return Optional.of(resilientClient.reserveSeat(
                    InventorySeatCall.reserve(flightId, seatNumber, bookingId, bookingPassengerId)));

        } catch (FeignException.NotFound notFound) {
            if (notFound.contentUTF8().contains(NO_INVENTORY_MARKER)) {
                return Optional.empty();
            }
            throw new SeatUnavailableException(flightId, seatNumber,
                    "seat does not exist in the flight's seat inventory");

        } catch (FeignException.Conflict conflict) {
            throw new SeatUnavailableException(flightId, seatNumber, "already reserved");

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

    /** Compensation/cleanup - never throws. */
    public void releaseHoldQuietly(Long flightId, String seatNumber, Long bookingId, String reason) {
        try {
            resilientClient.releaseHold(InventorySeatCall.release(flightId, seatNumber, bookingId, reason));
            log.info("Released hold on seat {} flight {} ({})", seatNumber, flightId, reason);
        } catch (FeignException | CallNotPermittedException | BulkheadFullException e) {
            log.warn("Could not release hold on seat {} flight {} ({}): {}",
                    seatNumber, flightId, reason, e.getMessage());
        }
    }

    /** Cleanup on booking cancellation - never throws. */
    public void cancelReservationQuietly(Long flightId, String seatNumber, Long bookingId, String reason) {
        try {
            resilientClient.cancelReservation(InventorySeatCall.release(flightId, seatNumber, bookingId, reason));
            log.info("Cancelled reservation of seat {} flight {} ({})", seatNumber, flightId, reason);
        } catch (FeignException | CallNotPermittedException | BulkheadFullException e) {
            log.warn("Could not cancel reservation of seat {} flight {} ({}): {}",
                    seatNumber, flightId, reason, e.getMessage());
        }
    }
}
