package com.skybook.praveen.inventoryservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Bulk seat-map creation for one aircraft - replaces nothing, only adds (idempotency handled in service). */
public record CreateSeatMapRequest(

        @NotEmpty(message = "At least one seat is required")
        @Size(max = 1000, message = "A seat map supports at most 1000 seats")
        @Valid
        List<CreateAircraftSeatRequest> seats

) {
}
