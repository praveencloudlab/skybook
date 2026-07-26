package com.skybook.praveen.checkinservice.mapper;

import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;

public final class CheckInMapper {

    private CheckInMapper() {
    }

    public static CheckInResponse toResponse(CheckIn checkIn) {
        return toResponse(checkIn, checkIn.getStatus());
    }

    /**
     * Map with an explicit DISPLAY status - used by the read path to show the
     * status reconciled against the clock (see CheckInValidator.effectiveStatus)
     * without mutating the stored row.
     */
    public static CheckInResponse toResponse(CheckIn checkIn, CheckInStatus displayStatus) {
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
                checkIn.getSeatSurchargeEntitlement(),
                checkIn.getEntitlementCurrency(),
                displayStatus,
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
