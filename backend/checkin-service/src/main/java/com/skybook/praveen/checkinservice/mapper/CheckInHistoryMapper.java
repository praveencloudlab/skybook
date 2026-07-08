package com.skybook.praveen.checkinservice.mapper;

import com.skybook.praveen.checkinservice.dto.response.CheckInHistoryResponse;
import com.skybook.praveen.checkinservice.entity.CheckInHistory;

public final class CheckInHistoryMapper {

    private CheckInHistoryMapper() {
    }

    public static CheckInHistoryResponse toResponse(CheckInHistory history) {
        return new CheckInHistoryResponse(
                history.getId(),
                history.getHistoryType(),
                history.getActor(),
                history.getSource(),
                history.getCorrelationId(),
                history.getDetails(),
                history.getChangedAt()
        );
    }
}
