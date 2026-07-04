package com.skybook.praveen.inventoryservice.dto.request;

import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One seat of a seat map - used standalone and inside CreateSeatMapRequest. */
public record CreateAircraftSeatRequest(

        @NotBlank(message = "seatNumber is required")
        @Size(max = 5, message = "seatNumber must be at most 5 characters")
        @Pattern(regexp = "\\d{1,3}[A-K]", message = "seatNumber must be row digits followed by a seat letter, e.g. 12A")
        String seatNumber,

        @NotNull(message = "rowNumber is required")
        @Min(value = 1, message = "rowNumber must be at least 1")
        Integer rowNumber,

        @NotNull(message = "seatType is required")
        SeatType seatType,

        @NotNull(message = "position is required")
        SeatPosition position,

        Boolean exitRow

) {
}
