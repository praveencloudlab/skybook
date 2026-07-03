package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.InventoryHistoryResponse;
import com.skybook.praveen.inventoryservice.entity.InventoryHistory;

public final class InventoryHistoryMapper {

    private InventoryHistoryMapper() {
    }

    public static InventoryHistoryResponse toResponse(InventoryHistory history) {
        return new InventoryHistoryResponse(
                history.getId(),
                history.getHistoryType(),
                history.getAircraftSeat() != null ? history.getAircraftSeat().getSeatNumber() : null,
                history.getBookingId(),
                history.getDetails(),
                history.getChangedAt()
        );
    }
}
