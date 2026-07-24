package com.skybook.praveen.checkinservice.client;

import com.skybook.praveen.checkinservice.config.InventoryCommandFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Reservation lookups/mutations check-in needs (design doc section 9.1). These
 * are internal service→service calls, so the client attaches check-in's
 * {@code ROLE_SERVICE} token (aud=inventory-service) via
 * {@link InventoryCommandFeignConfiguration} (§3.3) - inventory requires
 * ADMIN/SERVICE on the reservation surface.
 */
@FeignClient(name = "inventory-service", url = "${inventory-service.base-url}",
        contextId = "checkinInventoryServiceFeignClient",
        configuration = InventoryCommandFeignConfiguration.class)
public interface InventoryServiceFeignClient {

    @GetMapping("/api/reservations/booking/{bookingId}")
    List<SeatReservationDetails> getReservationsByBooking(@PathVariable Long bookingId);

    @PostMapping("/api/reservations")
    SeatReservationDetails reserveSeat(@RequestBody InventorySeatCall call);

    @PostMapping("/api/reservations/cancel")
    SeatReservationDetails cancelReservation(@RequestBody InventorySeatCall call);
}
