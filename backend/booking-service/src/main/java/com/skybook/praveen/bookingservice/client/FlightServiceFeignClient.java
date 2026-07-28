package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.security.UserTokenFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative Feign call to flight-service's GET /api/flights/{id}. Flight
 * validation happens inside the passenger's own booking request, so this
 * propagates the incoming USER/ADMIN token (§3.3, getFlightAsUser) via
 * {@link UserTokenFeignConfiguration} - once flight-service enforces auth
 * (§13 step 6), an unpropagated call would 401.
 */
@FeignClient(name = "flight-service", url = "${flight-service.base-url}",
        configuration = UserTokenFeignConfiguration.class)
public interface FlightServiceFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightDetails getFlight(@PathVariable("id") Long id);

    /**
     * Route availability for the fare calendar - public data on both ends.
     * The ISO annotation matters: without it Feign renders LocalDate with the
     * JVM locale (28/07/2026) and flight-service's ISO parser answers 400.
     */
    @GetMapping("/api/flights/calendar")
    java.util.List<RouteCalendarDay> getRouteCalendar(
            @org.springframework.web.bind.annotation.RequestParam("originAirportCode") String originAirportCode,
            @org.springframework.web.bind.annotation.RequestParam("destinationAirportCode") String destinationAirportCode,
            @org.springframework.web.bind.annotation.RequestParam("startDate")
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate startDate,
            @org.springframework.web.bind.annotation.RequestParam("endDate")
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate endDate);
}
