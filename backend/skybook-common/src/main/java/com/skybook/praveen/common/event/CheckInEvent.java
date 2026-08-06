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

    private String contactEmail;

    private Long flightId;

    private String flightNumber;

    private String originAirportCode;

    private String destinationAirportCode;

    private String seatNumber;

    /**
     * Flight departure, snapshotted from the CheckIn - lets the boarding-pass
     * email show DATE/DEPARTS and derive the boarding clock exactly like the
     * frontend pass does, without a synchronous flight-service call.
     */
    private LocalDateTime departureTime;

    /** Cabin (ECONOMY/PREMIUM_ECONOMY/BUSINESS/FIRST) for the pass's badge. */
    private String travelClass;

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

    /**
     * Real terminals (flight-service TerminalPolicy), snapshotted onto the
     * pass at issue - the emailed boarding pass must show the SAME terminals
     * as the on-screen one. Null on pre-terminals events.
     */
    private String departureTerminal;

    private String arrivalTerminal;

    /** When checkin-service issued the pass - the "Issued" stamp on the document. */
    private LocalDateTime issuedAt;

    private LocalDateTime occurredAt;

    /**
     * Set on boarding-pass email RE-SENDS (GUEST_CHECKIN_MODULE.md §5): a
     * per-request UUID that makes every delivery attributable in logs and
     * gives the consumer an idempotency key to dedupe on when the
     * transactional-outbox increment lands. Null on the original
     * check-in-time emission.
     */
    private String resendId;

    /** The subject that requested the re-send (owner, admin, or guest:<id>). */
    private String requestedBy;
}
