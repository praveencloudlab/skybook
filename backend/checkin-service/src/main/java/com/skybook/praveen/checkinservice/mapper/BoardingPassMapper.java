package com.skybook.praveen.checkinservice.mapper;

import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.entity.BoardingPass;

public final class BoardingPassMapper {

    private BoardingPassMapper() {
    }

    public static BoardingPassResponse toResponse(BoardingPass boardingPass) {
        return new BoardingPassResponse(
                boardingPass.getId(),
                boardingPass.getCheckIn().getId(),
                boardingPass.getBoardingPassNumber(),
                boardingPass.getToken(),
                boardingPass.getPassengerName(),
                boardingPass.getBookingReference(),
                boardingPass.getFlightNumber(),
                boardingPass.getOriginAirportCode(),
                boardingPass.getDestinationAirportCode(),
                boardingPass.getSeatNumber(),
                boardingPass.getGate(),
                boardingPass.getBoardingTime(),
                boardingPass.getBoardingGroup(),
                boardingPass.getStatus(),
                boardingPass.getIssuedAt(),
                boardingPass.getRevokedAt(),
                boardingPass.getReissuedAsId()
        );
    }
}
