package com.skybook.praveen.checkinservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PATCH /api/checkins/{id}/gate - design doc section 7 (added beyond the original brief). */
public record GateAssignmentRequest(

        @NotBlank(message = "gate is required")
        @Size(max = 10, message = "gate must be at most 10 characters")
        String gate

) {
}
