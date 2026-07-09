package com.skybook.praveen.checkinservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/** Declarative client for the reservation lookups/mutations checkin-service needs (design doc section 9.1). */
@FeignClient(name = "inventory-service", url = "${inventory-service.base-url}",
        contextId = "checkinInventoryServiceFeignClient")
public interface InventoryServiceFeignClient {

    @GetMapping("/api/reservations/booking/{bookingId}")
    List<SeatReservationDetails> getReservationsByBooking(@PathVariable Long bookingId);

    @PostMapping("/api/reservations")
    SeatReservationDetails reserveSeat(@RequestBody InventorySeatCall call);

    @PostMapping("/api/reservations/cancel")
    SeatReservationDetails cancelReservation(@RequestBody InventorySeatCall call);
}
