package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.entity.SeatReservation;

public final class SeatReservationMapper {

    private SeatReservationMapper() {
    }

    public static SeatReservationResponse toResponse(SeatReservation reservation) {
        return new SeatReservationResponse(
                reservation.getId(),
                reservation.getFlightInventory().getFlightId(),
                reservation.getAircraftSeat().getId(),
                reservation.getAircraftSeat().getSeatNumber(),
                reservation.getBookingId(),
                reservation.getBookingPassengerId(),
                reservation.getOriginatingHold() != null ? reservation.getOriginatingHold().getId() : null,
                reservation.getStatus(),
                reservation.getReservedAt(),
                reservation.getCancelledAt()
        );
    }
}
