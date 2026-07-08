package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One traveler inside a BookingEvent - used by notification-service's email template. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingEventPassenger {

    private String name;

    private String seatNumber;

    private String travelClass;

    private String fareType;

    private BigDecimal fare;

    /** e.g. "NOT_OPEN", "CHECKED_IN" - snapshot at event time */
    private String checkInStatus;
}
