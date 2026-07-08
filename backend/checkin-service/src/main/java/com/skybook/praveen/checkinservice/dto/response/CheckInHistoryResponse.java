package com.skybook.praveen.checkinservice.dto.response;

import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;

import java.time.LocalDateTime;

public record CheckInHistoryResponse(

        Long id,

        CheckInHistoryType historyType,

        String actor,

        String source,

        String correlationId,

        String details,

        LocalDateTime changedAt

) {
}
