package com.skybook.praveen.bookingservice.client;

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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceClientTest {

    @Mock
    private InventoryServiceFeignClient feignClient;

    private InventoryServiceClient client;

    @BeforeEach
    void setUp() {
        client = new InventoryServiceClient(feignClient);
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
        when(feignClient.holdSeat(any())).thenReturn(
                new InventoryHoldDetails(5L, "12A", "ACTIVE", LocalDateTime.now().plusMinutes(15)));

        Optional<InventoryHoldDetails> hold = client.holdSeat(10L, "12A", 42L);

        assertThat(hold).isPresent();
        assertThat(hold.get().status()).isEqualTo("ACTIVE");
    }

    @Test
    void missingFlightInventoryMeansSkipHolds() {
        // The hold-if-exists policy hinges on distinguishing this 404...
        when(feignClient.holdSeat(any())).thenThrow(notFound(
                "{\"message\":\"Flight inventory not found for flight id: 10\"}"));

        assertThat(client.holdSeat(10L, "12A", 42L)).isEmpty();
    }

    @Test
    void unknownSeatIn404IsAConflictNotASkip() {
        // ...from this one: inventory exists but the seat doesn't.
        when(feignClient.holdSeat(any())).thenThrow(notFound(
                "{\"message\":\"Seat 99Z not found on aircraft with id: 1\"}"));

        assertThatThrownBy(() -> client.holdSeat(10L, "99Z", 42L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void heldOrReservedSeatBecomesSeatUnavailable() {
        when(feignClient.holdSeat(any())).thenThrow(conflict());

        assertThatThrownBy(() -> client.holdSeat(10L, "12A", 42L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessageContaining("already held or reserved");
    }

    @Test
    void unreachableInventoryBecomes502() {
        when(feignClient.holdSeat(any())).thenThrow(down());

        assertThatThrownBy(() -> client.holdSeat(10L, "12A", 42L))
                .isInstanceOf(InventoryServiceUnavailableException.class);
    }

    @Test
    void reserveTranslatesTheSameWay() {
        when(feignClient.reserveSeat(any())).thenThrow(conflict());

        assertThatThrownBy(() -> client.reserveSeat(10L, "12A", 42L, 1L))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void quietMethodsNeverThrow() {
        when(feignClient.releaseHold(any())).thenThrow(conflict());
        when(feignClient.cancelReservation(any())).thenThrow(down());

        assertThatCode(() -> {
            client.releaseHoldQuietly(10L, "12A", 42L, "test");
            client.cancelReservationQuietly(10L, "12A", 42L, "test");
        }).doesNotThrowAnyException();
    }
}
