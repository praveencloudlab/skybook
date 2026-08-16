package com.skybook.praveen.bookingservice.producer;

import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventSegment;
import com.skybook.praveen.common.event.BookingEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes BookingEvent to Kafka for notification-service (emails) and
 * payment-service (payment lifecycle) - no synchronous REST calls.
 *
 * Called by BookingFacade AFTER BookingService's @Transactional method has
 * already returned (and therefore already committed) - see BookingFacade
 * for why that's deliberate instead of using @TransactionalEventListener.
 *
 * flight is nullable: the facade passes it when it has (or could fetch) the
 * flight details; the email template degrades gracefully without them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventProducer {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final KafkaTemplate<String, BookingEvent> kafkaTemplate;

    /** flights: one FlightDetails per segment, best-effort (null entries allowed). */
    public void publishBookingCreated(BookingResponse booking, List<FlightDetails> flights) {
        publish(booking, flights, BookingEventType.CREATED,
                "Your SkyBook booking " + booking.bookingReference() + " has been created",
                "Thanks for booking with SkyBook! Your booking reference is " + booking.bookingReference()
                        + ". Complete payment to confirm your seat(s).");
    }

    public void publishBookingConfirmed(BookingResponse booking, List<FlightDetails> flights) {
        publish(booking, flights, BookingEventType.CONFIRMED,
                "Your SkyBook booking " + booking.bookingReference() + " is confirmed",
                "Good news - your booking " + booking.bookingReference() + " is confirmed. Have a great flight.");
    }

    /**
     * refundTierPercent (CancellationPolicy) rides the event so payment-service
     * refunds exactly what the passenger was quoted: 100 = fare rules alone,
     * 50 = half, 0 = same-day forfeiture (no refund is created at all).
     */
    /**
     * refundBreakdown (nullable): the UPCOMING rows' fare lines - when a leg
     * already flew, payment-service refunds only the unused part of the
     * journey instead of the whole remaining capture. Null = legacy full
     * refund (unpaid bookings, or a desk cancel of a fully-departed one).
     */
    public void publishBookingCancelled(BookingResponse booking, List<FlightDetails> flights,
                                        int refundTierPercent, Integer premiumTierPercent,
                                        String refundBreakdown) {
        String refundLine = switch (refundTierPercent) {
            case 100 -> " If a refund is due, it will be processed shortly.";
            case 0 -> " Under the same-day cancellation policy no refund is due for this booking.";
            default -> " A " + refundTierPercent
                    + "% refund per your fare rules will be processed shortly.";
        };
        publish(booking, flights, BookingEventType.CANCELLED,
                "Your SkyBook booking " + booking.bookingReference() + " has been cancelled",
                "Your booking " + booking.bookingReference() + " has been cancelled." + refundLine,
                refundTierPercent, premiumTierPercent, refundBreakdown, null);
    }

    /**
     * Passengers or a segment cancelled off a SURVIVING booking: the money
     * facts ride the event so payment-service refunds exactly the cancelled
     * rows (breakdown x tier), and checkin-service closes exactly those
     * check-ins. refundLine is pre-worded by the facade ("2 passengers" /
     * "the return"), because only it knows what was cancelled.
     */
    public void publishBookingPartiallyCancelled(BookingResponse booking, List<FlightDetails> flights,
                                                 int refundTierPercent, Integer premiumTierPercent,
                                                 String refundBreakdown,
                                                 List<Long> cancelledRowIds, String what,
                                                 java.math.BigDecimal refundAmount) {
        String refundLine = refundTierPercent == 0 || refundAmount == null
                || refundAmount.signum() == 0
                ? " Under the cancellation policy no refund is due for this change."
                : " A refund of " + refundAmount.toPlainString()
                        + " GBP will be processed to your original payment method.";
        publish(booking, flights, BookingEventType.PARTIALLY_CANCELLED,
                "Your SkyBook booking " + booking.bookingReference() + " has been updated",
                "On booking " + booking.bookingReference() + ", " + what
                        + " has been cancelled. The rest of the booking is unchanged." + refundLine,
                refundTierPercent, premiumTierPercent, refundBreakdown, cancelledRowIds);
    }

    private void publish(BookingResponse booking, List<FlightDetails> flights,
                         BookingEventType type, String subject, String message) {
        publish(booking, flights, type, subject, message, null, null, null, null);
    }

    private void publish(BookingResponse booking, List<FlightDetails> flights,
                         BookingEventType type, String subject, String message,
                         Integer refundTierPercent) {
        publish(booking, flights, type, subject, message, refundTierPercent, null, null, null);
    }

    private void publish(BookingResponse booking, List<FlightDetails> flights,
                         BookingEventType type, String subject, String message, Integer refundTierPercent,
                         Integer premiumTierPercent, String refundBreakdown, List<Long> cancelledRowIds) {

        if (booking.contact() == null) {
            log.warn("Booking {} has no contact on file - skipping notification", booking.bookingReference());
            return;
        }

        Map<Long, FlightDetails> flightById = new HashMap<>();
        if (flights != null) {
            for (FlightDetails details : flights) {
                if (details != null) {
                    flightById.put(details.id(), details);
                }
            }
        }
        FlightDetails flight = flightById.get(booking.flightId());

        // rowId -> the traveller's e-ticket number (issued at CONFIRMED;
        // empty map before ticketing, so CREATED events carry none).
        Map<Long, String> ticketByRow = new HashMap<>();
        if (booking.tickets() != null) {
            booking.tickets().forEach(ticket -> ticket.coupons().forEach(
                    coupon -> ticketByRow.put(coupon.bookingPassengerId(), ticket.ticketNumber())));
        }

        BookingEvent.BookingEventBuilder event = BookingEvent.builder()
                .bookingReference(booking.bookingReference())
                .type(type)
                .contactEmail(booking.contact().contactEmail())
                .contactName(booking.contact().contactName())
                .subject(subject)
                .message(message)
                // Structured details for notification-service's HTML template
                // and payment-service's consumer.
                .bookingId(booking.id())
                .bookingStatus(booking.bookingStatus() != null ? booking.bookingStatus().name() : null)
                // Ownership rides EVERY event type (§4.2) so payment (CREATED)
                // and check-in (CONFIRMED) can snapshot it; null on legacy.
                .ownerSubject(booking.ownerSubject())
                .flightId(booking.flightId())
                .bookingDate(booking.bookingDate() != null
                        ? booking.bookingDate().format(EVENT_TIME) : null)
                // DEPRECATED flat mirror (ROUND_TRIP_MODULE.md §6) - kept one
                // release for old consumers and replayed old events; new
                // consumers read the nested segments below.
                .passengers(booking.passengers() == null ? null : booking.passengers().stream()
                        .map(p -> eventPassenger(p, ticketByRow))
                        .toList())
                .segments(booking.segments() == null || booking.segments().isEmpty() ? null
                        : booking.segments().stream()
                                .map(segment -> {
                                    FlightDetails details = flightById.get(segment.flightId());
                                    return BookingEventSegment.builder()
                                            .segmentIndex(segment.segmentIndex())
                                            .flightId(segment.flightId())
                                            .flightNumber(details != null ? details.flightNumber() : null)
                                            .originAirportCode(details != null ? details.originAirportCode() : null)
                                            .destinationAirportCode(details != null ? details.destinationAirportCode() : null)
                                            .departureTime(details != null && details.departureTime() != null
                                                    ? details.departureTime().format(EVENT_TIME) : null)
                                            .arrivalTime(details != null && details.arrivalTime() != null
                                                    ? details.arrivalTime().format(EVENT_TIME) : null)
                                            .departureTerminal(details != null ? details.departureTerminal() : null)
                                            .arrivalTerminal(details != null ? details.arrivalTerminal() : null)
                                            .passengers(booking.passengers() == null ? List.of()
                                                    : booking.passengers().stream()
                                                            .filter(p -> p.segmentIndex() == segment.segmentIndex())
                                                            .map(p -> eventPassenger(p, ticketByRow))
                                                            .toList())
                                            .build();
                                })
                                .toList())
                .totalFare(booking.totalFare())
                .taxTotal(booking.taxTotal())
                .taxBreakdown(booking.taxBreakdown())
                .refundTierPercent(refundTierPercent)
                .premiumTierPercent(premiumTierPercent)
                .refundBreakdown(refundBreakdown)
                .cancelledBookingPassengerIds(cancelledRowIds)
                .currency(booking.payment() != null ? booking.payment().currency() : null)
                .paymentStatus(booking.payment() != null && booking.payment().paymentStatus() != null
                        ? booking.payment().paymentStatus().name() : null);

        // Flight context - best-effort (null when flight-service was
        // unreachable or the caller had no reason to fetch it).
        if (flight != null) {
            event.flightNumber(flight.flightNumber())
                    .originAirportCode(flight.originAirportCode())
                    .destinationAirportCode(flight.destinationAirportCode())
                    .departureTime(flight.departureTime() != null
                            ? flight.departureTime().format(EVENT_TIME) : null)
                    .arrivalTime(flight.arrivalTime() != null
                            ? flight.arrivalTime().format(EVENT_TIME) : null);
        }

        // Async but no longer fire-and-forget: broker-side failures now log
        // at ERROR into the centralized pipeline (RESILIENCE_MODULE.md §10).
        kafkaTemplate.send(KafkaTopics.BOOKING_EVENTS, event.build())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} event for booking {} to {}",
                                type, booking.bookingReference(), KafkaTopics.BOOKING_EVENTS, ex);
                    }
                });

        log.info("Published {} event for booking {}", type, booking.bookingReference());
    }

    /**
     * Fare-watch mail (passenger features): a plain-text notification with no
     * booking behind it - notification-service's no-passengers fallback path
     * renders it; every other consumer ignores FARE_ALERT by type.
     */
    public void publishFareAlert(String email, String subjectLine, String message) {
        BookingEvent event = BookingEvent.builder()
                .type(BookingEventType.FARE_ALERT)
                .contactEmail(email)
                .contactName("traveller")
                .subject(subjectLine)
                .message(message)
                .build();
        kafkaTemplate.send(KafkaTopics.BOOKING_EVENTS, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish FARE_ALERT for {}", email, ex);
                    }
                });
    }

    private static BookingEventPassenger eventPassenger(
            com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse p,
            Map<Long, String> ticketByRow) {
        return BookingEventPassenger.builder()
                .bookingPassengerId(p.id())
                .segmentIndex(p.segmentIndex())
                .ticketNumber(ticketByRow.get(p.id()))
                .name((p.firstName() + " " + p.lastName()).trim())
                .seatNumber(p.seatNumber())
                .travelClass(p.travelClass() != null ? p.travelClass().name() : null)
                .fareType(p.fareType() != null ? p.fareType().name() : null)
                .fare(p.fare())
                // §9: check-in snapshots the surcharge actually
                // PAID as its free-change entitlement ceiling.
                .seatSurcharge(p.seatSurcharge())
                // Fare breakdown for the emailed ticket's ledger.
                .baseFare(p.baseFare())
                .baggageFee(p.baggageFee())
                .extraBags(p.extraBags())
                .currency(p.currency())
                .checkInStatus(p.checkInStatus() != null ? p.checkInStatus().name() : null)
                .build();
    }
}
