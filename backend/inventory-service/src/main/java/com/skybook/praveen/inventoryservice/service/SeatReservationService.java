package com.skybook.praveen.inventoryservice.service;

import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;

import java.util.List;

/** Owns SeatReservation rows - confirming holds into reservations and cancelling them. */
public interface SeatReservationService {

    /**
     * Confirms the booking's ACTIVE hold on the seat if one exists (holdId
     * optional - resolved by booking+seat otherwise); falls back to a direct
     * reservation when there is no hold.
     */
    SeatReservationResponse reserveSeat(ReserveSeatRequest request);

    SeatReservationResponse cancelReservation(ReleaseSeatRequest request);

    List<SeatReservationResponse> getReservationsByBooking(Long bookingId);

    List<SeatReservationResponse> getReservationsByFlight(Long flightId);
}
