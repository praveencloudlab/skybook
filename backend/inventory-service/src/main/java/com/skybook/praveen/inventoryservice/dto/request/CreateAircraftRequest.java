package com.skybook.praveen.inventoryservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAircraftRequest(

        @NotBlank(message = "registrationNumber is required")
        @Size(max = 10, message = "registrationNumber must be at most 10 characters")
        @Pattern(regexp = "[A-Z0-9-]+", message = "registrationNumber must be uppercase letters, digits and hyphens")
        String registrationNumber,

        @NotBlank(message = "manufacturer is required")
        @Size(max = 50, message = "manufacturer must be at most 50 characters")
        String manufacturer,

        @NotBlank(message = "model is required")
        @Size(max = 50, message = "model must be at most 50 characters")
        String model

) {
}
