package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.exception.FlightNotFoundForBookingException;
import com.skybook.praveen.bookingservice.exception.FlightServiceUnavailableException;
import com.skybook.praveen.bookingservice.exception.InventoryServiceUnavailableException;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the two-bean resilience structure behaves exactly as
 * RESILIENCE_MODULE.md §5/§6 claims - these tests exist because the first
 * design draft had the annotations on the wrong bean, where none of this
 * would have worked (the design review's central catch).
 *
 * Slice context: only the client beans + the Resilience4j/AOP
 * auto-configurations - no web, no JPA, no Kafka. The r4j instance config
 * comes from the module's real application.yml, so thresholds tested here
 * are the ones production runs with.
 */
@SpringBootTest(classes = ResilientClientBehaviorTest.SliceConfig.class)
class ResilientClientBehaviorTest {

    @Configuration
    @ImportAutoConfiguration({
            AopAutoConfiguration.class,
            io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration.class,
            io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration.class,
            io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration.class})
    @Import({ResilientFlightClient.class, FlightServiceClient.class,
            ResilientInventoryClient.class, InventoryServiceClient.class})
    static class SliceConfig {
    }

    @MockitoBean
    private FlightServiceFeignClient flightFeign;
    @MockitoBean
    private InventoryServiceFeignClient inventoryFeign;

    @Autowired
    private FlightServiceClient flightClient;
    @Autowired
    private InventoryServiceClient inventoryClient;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private static final Request REQUEST = Request.create(
            Request.HttpMethod.GET, "/test", Map.of(), null, new RequestTemplate());

    private static FeignException.InternalServerError serverError() {
        return new FeignException.InternalServerError("boom", REQUEST, null, Map.of());
    }

    private static FeignException.NotFound notFound() {
        return new FeignException.NotFound("nope", REQUEST, null, Map.of());
    }

    @BeforeEach
    void resetBreakers() {
        circuitBreakerRegistry.circuitBreaker("flight").reset();
        circuitBreakerRegistry.circuitBreaker("inventory").reset();
    }

    @Test
    void breakerOpensAfterServerErrorsAndFailsFastWithTheDomainException() {
        when(flightFeign.getFlight(anyLong())).thenThrow(serverError());

        // Real config: window 10, min calls 5, 50% threshold. Each wrapper
        // call = 3 recorded attempts (retry max-attempts=3), so two calls
        // push the breaker past its thresholds.
        for (int i = 0; i < 2; i++) {
            catchThrowable(() -> flightClient.getFlight(1L));
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("flight").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        clearInvocations(flightFeign);

        Throwable fastFail = catchThrowable(() -> flightClient.getFlight(1L));

        assertThat(fastFail)
                .isInstanceOf(FlightServiceUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        verify(flightFeign, times(0)).getFlight(anyLong()); // open circuit: no call even attempted
    }

    @Test
    void notFoundDoesNotTripTheBreakerAndKeepsItsDomainMeaning() {
        when(flightFeign.getFlight(anyLong())).thenThrow(notFound());

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> flightClient.getFlight(1L))
                    .isInstanceOf(FlightNotFoundForBookingException.class);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("flight").getState())
                .as("4xx proves the downstream is healthy - must not open the breaker")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void readsAreRetriedExactlyThreeTimesThenSurfaceTheDomainException() {
        when(flightFeign.getFlight(anyLong())).thenThrow(serverError());

        assertThatThrownBy(() -> flightClient.getFlight(1L))
                .isInstanceOf(FlightServiceUnavailableException.class);

        verify(flightFeign, times(3)).getFlight(anyLong());
    }

    @Test
    void notFoundIsNotRetried() {
        when(flightFeign.getFlight(anyLong())).thenThrow(notFound());

        catchThrowable(() -> flightClient.getFlight(1L));

        verify(flightFeign, times(1)).getFlight(anyLong());
    }

    @Test
    void writesAreNeverRetried() {
        when(inventoryFeign.holdSeat(any())).thenThrow(serverError());

        assertThatThrownBy(() -> inventoryClient.holdSeat(1L, "12B", 42L, 1L,
                com.skybook.praveen.bookingservice.enums.TravelClass.ECONOMY))
                .isInstanceOf(InventoryServiceUnavailableException.class);

        verify(inventoryFeign, times(1)).holdSeat(any()); // exactly one attempt - no write retries (§6)
    }

    @Test
    void openBreakerSuppressesRetriesEntirely() {
        circuitBreakerRegistry.circuitBreaker("flight").transitionToOpenState();

        catchThrowable(() -> flightClient.getFlight(1L));

        verify(flightFeign, times(0)).getFlight(anyLong()); // not even one retry attempt fires
    }

    @Test
    void quietCompensationMethodsSwallowOpenCircuitLikeAnyOtherFailure() {
        circuitBreakerRegistry.circuitBreaker("inventory").transitionToOpenState();

        inventoryClient.releaseHoldQuietly(1L, "12B", 42L, "test"); // must not throw

        verify(inventoryFeign, times(0)).releaseHold(any());
    }

    @Test
    void bulkheadAndBreakerExceptionsNeverLeakPastTheWrapper() {
        circuitBreakerRegistry.circuitBreaker("inventory").transitionToOpenState();

        Throwable t = catchThrowable(() -> inventoryClient.reserveSeat(1L, "12B", 42L, 7L));

        assertThat(t).isInstanceOf(InventoryServiceUnavailableException.class);
        assertThat(t).isNotInstanceOf(CallNotPermittedException.class);
        assertThat(t).isNotInstanceOf(BulkheadFullException.class);
    }
}
