package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.InventoryServiceUnavailableException;
import com.skybook.praveen.bookingservice.exception.SeatUnavailableException;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceClientTest {

    @Mock
    private InventoryCommandFeignClient command;
    @Mock
    private InventoryQueryFeignClient query;

    private InventoryServiceClient client;

    @BeforeEach
    void setUp() {
        // Plain construction, no Spring proxies: the resilient bean is a
        // pass-through here, so these tests keep exercising exactly the
        // wrapper's translation logic they always did. Aspect behavior
        // (breaker/bulkhead/retry) is covered by ResilientClientBehaviorTest.
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

    private static FeignException.Conflict conflict() {
        return new FeignException.Conflict("409", dummyRequest(),
                "{\"message\":\"Seat 12A on flight 10 is already held\"}".getBytes(StandardCharsets.UTF_8),
                Map.of());
    }

    private static FeignException.ServiceUnavailable down() {
        return new FeignException.ServiceUnavailable("503", dummyRequest(), null, Map.of());
    }

    @Test
    void successfulHoldPassesThrough() {
        when(command.holdSeat(any())).thenReturn(
                new InventoryHoldDetails(5L, "12A", "MANUAL", new BigDecimal("12.00"), new BigDecimal("12.00"), "ACTIVE", LocalDateTime.now().plusMinutes(15)));

        Optional<InventoryHoldDetails> hold = client.holdSeat(10L, "12A", 42L, 1L, TravelClass.ECONOMY);

        assertThat(hold).isPresent();
        assertThat(hold.get().status()).isEqualTo("ACTIVE");
    }

    @Test
    void missingFlightInventoryMeansSkipHolds() {
        // The hold-if-exists policy hinges on distinguishing this 404...
        when(command.holdSeat(any())).thenThrow(notFound(
                "{\"message\":\"Flight inventory not found for flight id: 10\"}"));

        assertThat(client.holdSeat(10L, "12A", 42L, 1L, TravelClass.ECONOMY)).isEmpty();
    }

    @Test
    void unknownSeatIn404IsAConflictNotASkip() {
        // ...from this one: inventory exists but the seat doesn't.
        when(command.holdSeat(any())).thenThrow(notFound(
                "{\"message\":\"Seat 99Z not found on aircraft with id: 1\"}"));

        assertThatThrownBy(() -> client.holdSeat(10L, "99Z", 42L, 1L, TravelClass.ECONOMY))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void heldOrReservedSeatBecomesSeatUnavailable() {
        when(command.holdSeat(any())).thenThrow(conflict());

        // Inventory's own 409 message (naming the violated rule) is surfaced.
        assertThatThrownBy(() -> client.holdSeat(10L, "12A", 42L, 1L, TravelClass.ECONOMY))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessageContaining("already held");
    }

    @Test
    void unreachableInventoryBecomes502() {
        when(command.holdSeat(any())).thenThrow(down());

        assertThatThrownBy(() -> client.holdSeat(10L, "12A", 42L, 1L, TravelClass.ECONOMY))
                .isInstanceOf(InventoryServiceUnavailableException.class);
    }

    @Test
    void autoHoldPassesThroughWithItsPricingSnapshot() {
        when(command.autoHoldSeat(eq(10L), any())).thenReturn(
                new InventoryHoldDetails(6L, "20B", "AUTO", new BigDecimal("12.00"),
                        new BigDecimal("0.00"), "ACTIVE", LocalDateTime.now().plusMinutes(15)));

        Optional<InventoryHoldDetails> hold = client.autoHoldSeat(10L, 42L, 1L, TravelClass.ECONOMY);

        assertThat(hold).isPresent();
        assertThat(hold.get().assignmentMode()).isEqualTo("AUTO");
        assertThat(hold.get().chargedSurcharge()).isEqualByComparingTo("0.00");
    }

    @Test
    void autoHoldOnFlightWithoutInventoryMeansSkipHolds() {
        when(command.autoHoldSeat(eq(10L), any())).thenThrow(notFound(
                "{\"message\":\"Flight inventory not found for flight id: 10\"}"));

        assertThat(client.autoHoldSeat(10L, 42L, 1L, TravelClass.ECONOMY)).isEmpty();
    }

    @Test
    void exhaustedCabinBecomesSeatUnavailable() {
        when(command.autoHoldSeat(eq(10L), any())).thenThrow(conflict());

        assertThatThrownBy(() -> client.autoHoldSeat(10L, 42L, 1L, TravelClass.ECONOMY))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void reserveTranslatesTheSameWay() {
        when(command.reserveSeat(any())).thenThrow(conflict());

        assertThatThrownBy(() -> client.reserveSeat(10L, "12A", 42L, 1L))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void quietMethodsNeverThrow() {
        when(command.releaseHold(any())).thenThrow(conflict());
        when(command.cancelReservation(any())).thenThrow(down());

        assertThatCode(() -> {
            client.releaseHoldQuietly(10L, "12A", 42L, "test");
            client.cancelReservationQuietly(10L, "12A", 42L, "test");
        }).doesNotThrowAnyException();
    }
}
