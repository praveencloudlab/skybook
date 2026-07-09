package com.skybook.praveen.checkinservice.client;

import com.skybook.praveen.checkinservice.exception.InventoryServiceUnavailableException;
import com.skybook.praveen.checkinservice.exception.SeatUnavailableException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Seat-reservation boundary for checkin-service (design doc section 9.1):
 * confirm a passenger's seat is RESERVED before allowing check-in, and move
 * a reservation on seat change (reserve the new seat, then cancel the old
 * one - no hold step, inventory-service reserves directly from AVAILABLE
 * when no hold exists, same as booking-service's "reserveDirect" path).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryServiceClient {

    private static final String NO_INVENTORY_MARKER = "Flight inventory not found";

    private final InventoryServiceFeignClient feignClient;

    /**
     * Never throws - the GET endpoint has no flight/inventory lookup of its
     * own, so an empty list means either "no reservations" or "no inventory
     * tracked for this flight at all." Either way, callers treat an empty
     * list as "nothing to validate against" (booking-service's same
     * hold-if-exists graceful-degradation policy, applied here to reads).
     */
    public List<SeatReservationDetails> getReservationsForBooking(Long bookingId) {
        return feignClient.getReservationsByBooking(bookingId);
    }

    /** Optional.empty() = the flight has no inventory record - proceed without reserving. */
    public Optional<SeatReservationDetails> reserveSeat(Long flightId, String seatNumber,
                                                         Long bookingId, Long bookingPassengerId) {
        try {
            return Optional.of(feignClient.reserveSeat(
                    InventorySeatCall.reserve(flightId, seatNumber, bookingId, bookingPassengerId)));

        } catch (FeignException.NotFound notFound) {
            if (notFound.contentUTF8().contains(NO_INVENTORY_MARKER)) {
                return Optional.empty();
            }
            throw new SeatUnavailableException(flightId, seatNumber,
                    "seat does not exist in the flight's seat inventory");

        } catch (FeignException.Conflict conflict) {
            throw new SeatUnavailableException(flightId, seatNumber, "already held or reserved");

        } catch (FeignException unreachable) {
            log.error("Could not reach inventory-service to reserve seat {} on flight {}",
                    seatNumber, flightId, unreachable);
            throw new InventoryServiceUnavailableException(flightId, unreachable);
        }
    }

    /** Compensation/cleanup for the old seat on a successful seat change - never throws. */
    public void cancelReservationQuietly(Long flightId, String seatNumber, Long bookingId, String reason) {
        try {
            feignClient.cancelReservation(InventorySeatCall.cancel(flightId, seatNumber, bookingId, reason));
            log.info("Cancelled reservation of seat {} flight {} ({})", seatNumber, flightId, reason);
        } catch (FeignException e) {
            log.warn("Could not cancel reservation of seat {} flight {} ({}): {}",
                    seatNumber, flightId, reason, e.getMessage());
        }
    }
}
