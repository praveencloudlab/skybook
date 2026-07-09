package com.skybook.praveen.checkinservice.mapper;

import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.entity.CheckIn;

public final class CheckInMapper {

    private CheckInMapper() {
    }

    public static CheckInResponse toResponse(CheckIn checkIn) {
        return new CheckInResponse(
                checkIn.getId(),
                checkIn.getBookingId(),
                checkIn.getBookingReference(),
                checkIn.getBookingPassengerId(),
                checkIn.getFlightId(),
                checkIn.getFlightNumber(),
                checkIn.getOriginAirportCode(),
                checkIn.getDestinationAirportCode(),
                checkIn.getDepartureTime(),
                checkIn.getPassengerName(),
                checkIn.getContactEmail(),
                checkIn.getSeatNumber(),
                checkIn.getTravelClass(),
                checkIn.getFareType(),
                checkIn.getStatus(),
                checkIn.isDocumentVerified(),
                checkIn.getCheckedInAt(),
                checkIn.getBoardedAt(),
                checkIn.getGate(),
                checkIn.getBoardingGroup(),
                checkIn.getVersion(),
                checkIn.getCreatedAt(),
                checkIn.getUpdatedAt()
        );
    }
}
