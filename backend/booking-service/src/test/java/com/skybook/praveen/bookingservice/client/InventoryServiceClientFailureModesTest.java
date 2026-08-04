package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.InventoryServiceUnavailableException;
import com.skybook.praveen.bookingservice.exception.SeatUnavailableException;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Companion to {@link InventoryServiceClientTest}, which owns the manual-hold
 * translation table. This one covers the paths a healthy inventory-service
 * never reaches: the resilience fast-fails (open circuit, saturated bulkhead)
 * and the read/reserve/cleanup operations - all of which must arrive at the
 * caller as this module's own exceptions, never as Feign or Resilience4j
 * types leaking through the boundary.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceClientFailureModesTest {

    @Mock
    private InventoryCommandFeignClient command;
    @Mock
    private InventoryQueryFeignClient query;

    @Captor
    private ArgumentCaptor<InventorySeatCall> callCaptor;

    private InventoryServiceClient client;

    @BeforeEach
    void setUp() {
        client = new InventoryServiceClient(new ResilientInventoryClient(command, query));
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.POST, "/api/inventory/hold",
                Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());
    }

    private static FeignException.NotFound notFound(String body) {
        return new FeignException.NotFound("404", dummyRequest(),
                body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static FeignException.Conflict conflict(String body) {
        return new FeignException.Conflict("409", dummyRequest(),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static FeignException.ServiceUnavailable down() {
        return new FeignException.ServiceUnavailable("503", dummyRequest(), null, Map.of());
    }

    private static CallNotPermittedException openCircuit() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("inventory");
        breaker.transitionToOpenState();
        return CallNotPermittedException.createCallNotPermittedException(breaker);
    }

    private static BulkheadFullException saturatedBulkhead() {
        return BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("inventory"));
    }

    @Nested
    @DisplayName("resilience fast-fails never leak past the boundary")
    class FastFails {

        @Test
        @DisplayName("an open circuit on a manual hold becomes a 502-mapped domain exception")
        void openCircuitOnManualHold() {
            when(command.holdSeat(any())).thenThrow(openCircuit());

            assertThatThrownBy(() -> client.holdSeat(10L, "12A", 42L, 1L, TravelClass.ECONOMY))
                    .isInstanceOf(InventoryServiceUnavailableException.class)
                    .hasMessageContaining("flight 10")
                    .hasCauseInstanceOf(CallNotPermittedException.class);
        }

        @Test
        @DisplayName("a saturated bulkhead on a manual hold is the same unavailability")
        void saturatedBulkheadOnManualHold() {
            when(command.holdSeat(any())).thenThrow(saturatedBulkhead());

            assertThatThrownBy(() -> client.holdSeat(10L, "12A", 42L, 1L, TravelClass.ECONOMY))
                    .isInstanceOf(InventoryServiceUnavailableException.class)
                    .hasCauseInstanceOf(BulkheadFullException.class);
        }

        @Test
        @DisplayName("an open circuit on an auto-hold fails the booking rather than seating blind")
        void openCircuitOnAutoHold() {
            when(command.autoHoldSeat(eq(10L), any())).thenThrow(openCircuit());

            assertThatThrownBy(() -> client.autoHoldSeat(10L, 42L, 1L, TravelClass.ECONOMY))
                    .isInstanceOf(InventoryServiceUnavailableException.class)
                    .hasCauseInstanceOf(CallNotPermittedException.class);
        }

        @Test
        @DisplayName("a saturated bulkhead on the cabin read fails the quote, it does not fake availability")
        void saturatedBulkheadOnCabinRead() {
            when(query.getCabins(10L)).thenThrow(saturatedBulkhead());

            assertThatThrownBy(() -> client.getCabins(10L))
                    .isInstanceOf(InventoryServiceUnavailableException.class)
                    .hasCauseInstanceOf(BulkheadFullException.class);
        }

        @Test
        @DisplayName("an open circuit on a reservation surfaces as unavailability, not a seat conflict")
        void openCircuitOnReserve() {
            when(command.reserveSeat(any())).thenThrow(openCircuit());

            assertThatThrownBy(() -> client.reserveSeat(10L, "12A", 42L, 1L))
                    .isInstanceOf(InventoryServiceUnavailableException.class)
                    .isNotInstanceOf(SeatUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("auto-hold")
    class AutoHold {

        @Test
        @DisplayName("a 404 that is not the no-inventory marker means the cabin could not be seated")
        void aNonInventory404MeansTheCabinCouldNotBeSeated() {
            when(command.autoHoldSeat(eq(10L), any())).thenThrow(notFound(
                    "{\"message\":\"No ECONOMY cabin on aircraft 1\"}"));

            assertThatThrownBy(() -> client.autoHoldSeat(10L, 42L, 1L, TravelClass.ECONOMY))
                    .isInstanceOf(SeatUnavailableException.class)
                    .hasMessageContaining("(auto)")
                    .hasMessageContaining("auto-assignment failed")
                    // Inventory's own wording is preserved so the 409 explains itself.
                    .hasMessageContaining("No ECONOMY cabin");
        }

        @Test
        @DisplayName("an unreachable inventory-service fails the auto-hold")
        void anUnreachableInventoryFailsTheAutoHold() {
            when(command.autoHoldSeat(eq(10L), any())).thenThrow(down());

            assertThatThrownBy(() -> client.autoHoldSeat(10L, 42L, 1L, TravelClass.ECONOMY))
                    .isInstanceOf(InventoryServiceUnavailableException.class);
        }

        @Test
        @DisplayName("the auto-hold call carries no seat - inventory picks one")
        void theAutoHoldCallCarriesNoSeat() {
            when(command.autoHoldSeat(eq(10L), any())).thenThrow(down());

            assertThatThrownBy(() -> client.autoHoldSeat(10L, 42L, 7L, TravelClass.BUSINESS))
                    .isInstanceOf(InventoryServiceUnavailableException.class);

            verify(command).autoHoldSeat(eq(10L), callCaptor.capture());
            assertThat(callCaptor.getValue().seatNumber()).isNull();
            assertThat(callCaptor.getValue().bookingPassengerId()).isEqualTo(7L);
            assertThat(callCaptor.getValue().travelClass()).isEqualTo(TravelClass.BUSINESS);
        }
    }

    @Nested
    @DisplayName("cabin availability for the quote")
    class Cabins {

        @Test
        @DisplayName("what inventory reports is passed straight through to the quote")
        void inventorysCabinsArePassedThrough() {
            when(query.getCabins(10L)).thenReturn(List.of(
                    new InventoryCabinDetails(TravelClass.ECONOMY, 180, 42),
                    new InventoryCabinDetails(TravelClass.BUSINESS, 20, 0)));

            Optional<List<InventoryCabinDetails>> cabins = client.getCabins(10L);

            assertThat(cabins).isPresent();
            assertThat(cabins.get())
                    .extracting(InventoryCabinDetails::travelClass, InventoryCabinDetails::availableSeats)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(TravelClass.ECONOMY, 42),
                            org.assertj.core.groups.Tuple.tuple(TravelClass.BUSINESS, 0));
        }

        @Test
        @DisplayName("a flight with no inventory record quotes fares without availability")
        void aFlightWithoutInventoryQuotesWithoutAvailability() {
            when(query.getCabins(10L)).thenThrow(notFound(
                    "{\"message\":\"Flight inventory not found for flight id: 10\"}"));

            assertThat(client.getCabins(10L)).isEmpty();
        }

        @Test
        @DisplayName("any other 404 is a broken integration, not a legal empty answer")
        void anyOther404IsABrokenIntegration() {
            when(query.getCabins(10L)).thenThrow(notFound("{\"message\":\"No handler for this path\"}"));

            assertThatThrownBy(() -> client.getCabins(10L))
                    .isInstanceOf(InventoryServiceUnavailableException.class);
        }

        @Test
        @DisplayName("an unreachable inventory-service fails the cabin read")
        void anUnreachableInventoryFailsTheCabinRead() {
            when(query.getCabins(10L)).thenThrow(down());

            assertThatThrownBy(() -> client.getCabins(10L))
                    .isInstanceOf(InventoryServiceUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("reservation")
    class Reserve {

        @Test
        @DisplayName("a successful reservation is passed through with inventory's status")
        void aSuccessfulReservationIsPassedThrough() {
            when(command.reserveSeat(any()))
                    .thenReturn(new InventoryReservationDetails(8L, "12A", "RESERVED"));

            Optional<InventoryReservationDetails> reservation = client.reserveSeat(10L, "12A", 42L, 1L);

            assertThat(reservation).isPresent();
            assertThat(reservation.get().status()).isEqualTo("RESERVED");
            assertThat(reservation.get().seatNumber()).isEqualTo("12A");
        }

        @Test
        @DisplayName("a flight with no inventory record reserves nothing and says so")
        void aFlightWithoutInventoryReservesNothing() {
            when(command.reserveSeat(any())).thenThrow(notFound(
                    "{\"message\":\"Flight inventory not found for flight id: 10\"}"));

            assertThat(client.reserveSeat(10L, "12A", 42L, 1L)).isEmpty();
        }

        @Test
        @DisplayName("a seat missing from the seat map cannot be sold")
        void aSeatMissingFromTheSeatMapCannotBeSold() {
            when(command.reserveSeat(any())).thenThrow(notFound(
                    "{\"message\":\"Seat 99Z not found on aircraft with id: 1\"}"));

            assertThatThrownBy(() -> client.reserveSeat(10L, "99Z", 42L, 1L))
                    .isInstanceOf(SeatUnavailableException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        @DisplayName("an unreachable inventory-service fails the reservation")
        void anUnreachableInventoryFailsTheReservation() {
            when(command.reserveSeat(any())).thenThrow(down());

            assertThatThrownBy(() -> client.reserveSeat(10L, "12A", 42L, 1L))
                    .isInstanceOf(InventoryServiceUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("compensation calls")
    class Compensation {

        @Test
        @DisplayName("releasing a hold sends the seat, booking and the reason it was released")
        void releasingAHoldSendsTheReason() {
            client.releaseHoldQuietly(10L, "12A", 42L, "booking failed");

            verify(command).releaseHold(callCaptor.capture());
            InventorySeatCall sent = callCaptor.getValue();
            assertThat(sent.flightId()).isEqualTo(10L);
            assertThat(sent.seatNumber()).isEqualTo("12A");
            assertThat(sent.bookingId()).isEqualTo(42L);
            assertThat(sent.reason()).isEqualTo("booking failed");
        }

        @Test
        @DisplayName("cancelling a reservation sends the same shape on the cancellation path")
        void cancellingAReservationSendsTheSameShape() {
            when(command.cancelReservation(any())).thenReturn(
                    new InventoryReservationDetails(8L, "12A", "CANCELLED"));

            client.cancelReservationQuietly(10L, "12A", 42L, "booking cancelled");

            verify(command).cancelReservation(callCaptor.capture());
            assertThat(callCaptor.getValue().reason()).isEqualTo("booking cancelled");
            assertThat(callCaptor.getValue().bookingPassengerId()).isNull();
        }
    }

    @Nested
    @DisplayName("conflict wording")
    class ConflictWording {

        @Test
        @DisplayName("an empty 409 body falls back to a message that still explains the refusal")
        void anEmpty409BodyFallsBackToAReadableReason() {
            when(command.holdSeat(any())).thenThrow(conflict(""));

            assertThatThrownBy(() -> client.holdSeat(10L, "12A", 42L, 1L, TravelClass.ECONOMY))
                    .isInstanceOf(SeatUnavailableException.class)
                    .hasMessageContaining("already held or reserved");
        }

        @Test
        @DisplayName("a 409 on an auto-hold reports the exhausted cabin, keyed as (auto)")
        void a409OnAutoHoldReportsTheExhaustedCabin() {
            when(command.autoHoldSeat(eq(10L), any()))
                    .thenThrow(conflict("{\"message\":\"ECONOMY cabin is full\"}"));

            assertThatThrownBy(() -> client.autoHoldSeat(10L, 42L, 1L, TravelClass.ECONOMY))
                    .isInstanceOf(SeatUnavailableException.class)
                    .hasMessageContaining("(auto)")
                    .hasMessageContaining("ECONOMY cabin is full");
        }
    }
}
