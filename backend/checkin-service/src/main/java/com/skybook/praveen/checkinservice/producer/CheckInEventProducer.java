package com.skybook.praveen.checkinservice.producer;

import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.common.event.CheckInEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Publishes CheckInEvent to skybook.checkin.events. Called by CheckInFacade
 * AFTER the service transaction has committed - same after-commit rationale
 * as every other producer in the fleet. Consumed by booking-service to keep
 * BookingPassenger.checkInStatus as a denormalized read-model (design doc
 * section 8/11).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckInEventProducer {

    private final KafkaTemplate<String, CheckInEvent> kafkaTemplate;

    public void publishPassengerCheckedIn(CheckInResponse checkIn) {
        publish(base(CheckInEventType.PASSENGER_CHECKED_IN, checkIn).build());
    }

    /**
     * Takes the full BoardingPassResponse (not just the number) - token,
     * boardingTime and boardingGroup let notification-service render the
     * QR/pass without a synchronous call back here.
     */
    public void publishBoardingPassGenerated(CheckInResponse checkIn, BoardingPassResponse pass) {
        publish(base(CheckInEventType.BOARDING_PASS_GENERATED, checkIn)
                .boardingPassNumber(pass.boardingPassNumber())
                .token(pass.token())
                .boardingTime(pass.boardingTime())
                .boardingGroup(pass.boardingGroup())
                .build());
    }

    public void publishPassengerBoarded(CheckInResponse checkIn) {
        publish(base(CheckInEventType.PASSENGER_BOARDED, checkIn).build());
    }

    public void publishPassengerNoShow(CheckInResponse checkIn) {
        publish(base(CheckInEventType.PASSENGER_NO_SHOW, checkIn).build());
    }

    public void publishPassengerCheckInCancelled(CheckInResponse checkIn) {
        publish(base(CheckInEventType.PASSENGER_CHECKIN_CANCELLED, checkIn).build());
    }

    private CheckInEvent.CheckInEventBuilder base(CheckInEventType type, CheckInResponse checkIn) {
        return CheckInEvent.builder()
                .type(type)
                .checkInId(checkIn.id())
                .bookingId(checkIn.bookingId())
                .bookingReference(checkIn.bookingReference())
                .bookingPassengerId(checkIn.bookingPassengerId())
                .passengerName(checkIn.passengerName())
                .contactEmail(checkIn.contactEmail())
                .flightId(checkIn.flightId())
                .flightNumber(checkIn.flightNumber())
                .originAirportCode(checkIn.originAirportCode())
                .destinationAirportCode(checkIn.destinationAirportCode())
                .seatNumber(checkIn.seatNumber())
                .gate(checkIn.gate())
                .occurredAt(LocalDateTime.now());
    }

    private void publish(CheckInEvent event) {
        // Async but no longer fire-and-forget: broker-side failures now log
        // at ERROR into the centralized pipeline (RESILIENCE_MODULE.md §10).
        kafkaTemplate.send(KafkaTopics.CHECKIN_EVENTS, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} event for check-in {} to {}",
                                event.getType(), event.getCheckInId(), KafkaTopics.CHECKIN_EVENTS, ex);
                    }
                });
        log.info("Published {} event for check-in {} (booking {})",
                event.getType(), event.getCheckInId(), event.getBookingReference());
    }
}
