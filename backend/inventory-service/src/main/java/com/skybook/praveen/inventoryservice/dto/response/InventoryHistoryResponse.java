package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;

import java.time.LocalDateTime;

public record InventoryHistoryResponse(

        Long id,

        InventoryHistoryType historyType,

        String seatNumber,

        Long bookingId,

        String details,

        LocalDateTime changedAt

) {
}
