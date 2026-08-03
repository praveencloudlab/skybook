package com.skybook.praveen.bookingservice.consumer;

import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.service.BookingService;
import com.skybook.praveen.common.event.CheckInEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Mirrors checkin-service's authoritative per-passenger state onto
 * {@code BookingPassenger.checkInStatus} (docs section 11 - the read-model
 * this service's own stopgap endpoints were standing in for).
 *
 * <p>This closes a real hole: without the mirror the cancel-passengers guard
 * ("a checked-in traveller cannot be cancelled online") compared against a
 * status that never left NOT_OPEN, so a passenger holding a freshly issued
 * boarding pass could still be cancelled - releasing a seat someone was about
 * to board with.
 *
 * <p>Failures are logged, not rethrown - a booking-side bug must not poison
 * the check-in topic with endless redeliveries; checkin-service remains the
 * source of truth either way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckInEventConsumer {

    private final BookingService bookingService;

    @KafkaListener(
            topics = "${skybook.kafka.topics.checkin-events}",
            containerFactory = "checkInEventContainerFactory")
    public void consume(CheckInEvent event) {

        log.info("Received CheckIn Event: {} for booking {} (passenger {})",
                event.getType(), event.getBookingReference(), event.getBookingPassengerId());

        if (event.getType() == null || event.getBookingId() == null || event.getBookingPassengerId() == null) {
            log.warn("Check-in event missing type/bookingId/bookingPassengerId - skipping");
            return;
        }

        // BOARDING_PASS_GENERATED is not a state change - but a REISSUED pass
        // (post-check-in seat change) carries the passenger's NEW seat, and
        // the mirror must track it or a later cancel releases the wrong seat.
        // A pass only exists for a checked-in passenger, so CHECKED_IN is the
        // honest target; applyCheckInStatus mirrors the seat first and treats
        // an already-matching status as the normal no-op it is.
        CheckInStatus target = switch (event.getType()) {
            case PASSENGER_CHECKED_IN, BOARDING_PASS_GENERATED -> CheckInStatus.CHECKED_IN;
            case PASSENGER_BOARDED -> CheckInStatus.BOARDED;
            case PASSENGER_NO_SHOW -> CheckInStatus.NO_SHOW;
            case PASSENGER_CHECKIN_CANCELLED -> CheckInStatus.CLOSED;
        };

        try {
            bookingService.applyCheckInStatus(event.getBookingId(), event.getBookingPassengerId(), target,
                    event.getSeatNumber());
        } catch (RuntimeException e) {
            log.error("Failed to mirror {} for booking {} passenger {} - read-model may lag until the next event",
                    event.getType(), event.getBookingReference(), event.getBookingPassengerId(), e);
        }
    }
}
