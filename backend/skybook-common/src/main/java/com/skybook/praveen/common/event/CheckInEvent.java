package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Published by checkin-service (docs/CHECKIN_SERVICE_MODULE.md section 8) -
 * mirrors PaymentEvent/BookingEvent style. Consumed by booking-service to
 * keep BookingPassenger.checkInStatus as a denormalized read-model (design
 * doc section 11) once booking-service's own stopgap check-in endpoints are
 * retired.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInEvent {

    private CheckInEventType type;

    private Long checkInId;

    private Long bookingId;

    private String bookingReference;

    private Long bookingPassengerId;

    private String passengerName;

    private Long flightId;

    private String flightNumber;

    private String originAirportCode;

    private String destinationAirportCode;

    private String seatNumber;

    /** Set on PASSENGER_CHECKED_IN / BOARDING_PASS_GENERATED. */
    private String boardingPassNumber;

    /**
     * The signed boarding-pass token (checkin-service's BoardingPassTokenSigner
     * output) - set on BOARDING_PASS_GENERATED only, so notification-service
     * can render the same QR without a synchronous call back to checkin-service
     * (fleet convention: notification-service is purely event-driven).
     */
    private String token;

    private LocalDateTime boardingTime;

    private String boardingGroup;

    private String gate;

    private LocalDateTime occurredAt;
}
