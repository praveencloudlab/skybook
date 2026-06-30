package com.skybook.praveen.flightservice.dto.request;

public record CancelFlightScheduleRequest(

        String reason,

        String remarks

) {
}
