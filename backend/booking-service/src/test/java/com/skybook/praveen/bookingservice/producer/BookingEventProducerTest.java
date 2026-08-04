package com.skybook.praveen.bookingservice.producer;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.dto.response.BookingContactResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingPaymentResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingSegmentResponse;
import com.skybook.praveen.bookingservice.dto.response.TicketResponse;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TicketStatus;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventSegment;
import com.skybook.praveen.common.event.BookingEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The producer is the ONLY thing three downstream services see of a booking:
 * notification renders the email from it, payment moves money off the refund
 * fields, and check-in closes exactly the rows named on it. Everything below
 * asserts the captured payload rather than "did we call send", because a
 * wrong tier or a missing row id is silent money loss, not a crash.
 */
@ExtendWith(MockitoExtension.class)
class BookingEventProducerTest {

    @Mock
    private KafkaTemplate<String, BookingEvent> kafkaTemplate;

    @InjectMocks
    private BookingEventProducer producer;

    @Captor
    private ArgumentCaptor<BookingEvent> eventCaptor;

    // Anchored to today so the fixture never rots into a past-dated booking.
    private static final LocalDateTime BOOKED_AT = LocalDateTime.now()
            .withHour(14).withMinute(5).withSecond(30).withNano(0);
    private static final LocalDateTime OUTBOUND_DEPARTS = LocalDateTime.now().plusDays(30)
            .withHour(9).withMinute(25).withSecond(0).withNano(0);
    private static final LocalDateTime OUTBOUND_ARRIVES = OUTBOUND_DEPARTS.plusHours(7).plusMinutes(40);
    private static final LocalDateTime RETURN_DEPARTS = LocalDateTime.now().plusDays(37)
            .withHour(18).withMinute(0).withSecond(0).withNano(0);

    @BeforeEach
    void acknowledgeEverySend() {
        CompletableFuture<SendResult<String, BookingEvent>> ack = CompletableFuture.completedFuture(null);
        lenient().when(kafkaTemplate.send(anyString(), any(BookingEvent.class))).thenReturn(ack);
    }

    private BookingEvent captureSentEvent() {
        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_EVENTS), eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private static FlightDetails outboundFlight() {
        return new FlightDetails(9L, "SB101", "LHR", "JFK",
                OUTBOUND_DEPARTS, OUTBOUND_ARRIVES, "T5", "T7", FlightBookingStatus.SCHEDULED);
    }

    private static FlightDetails returnFlight() {
        return new FlightDetails(10L, "SB102", "JFK", "LHR",
                RETURN_DEPARTS, RETURN_DEPARTS.plusHours(6), "T7", "T5", FlightBookingStatus.SCHEDULED);
    }

    private static BookingPassengerResponse passenger(Long rowId, int segmentIndex, Long flightId,
                                                      String first, String last, String seat) {
        return new BookingPassengerResponse(
                rowId, 500L, segmentIndex, flightId,
                first, last, "P1234567",
                "Mr", "MALE", LocalDate.now().minusYears(34), "GB", LocalDate.now().plusYears(5),
                TravelClass.ECONOMY, FareType.SAVER, seat,
                new BigDecimal("100.00"), new BigDecimal("12.00"), 1, new BigDecimal("30.00"),
                SeatAssignmentMode.MANUAL, "GBP", new BigDecimal("142.00"),
                CheckInStatus.NOT_OPEN, false, "ADULT");
    }

    private static TicketResponse ticket(String ticketNumber, Long... rowIds) {
        List<TicketResponse.TicketCouponResponse> coupons = new java.util.ArrayList<>();
        for (int i = 0; i < rowIds.length; i++) {
            coupons.add(new TicketResponse.TicketCouponResponse(i + 1, i, rowIds[i], CouponStatus.OPEN));
        }
        return new TicketResponse(1L, ticketNumber, 500L, TicketStatus.ISSUED, BOOKED_AT, coupons);
    }

    /** One-way, one passenger, paid - the shape most events carry. */
    private static BookingResponse booking(List<BookingPassengerResponse> passengers,
                                           List<BookingSegmentResponse> segments,
                                           List<TicketResponse> tickets) {
        return new BookingResponse(
                77L, "SB1234", 500L, 9L,
                segments,
                BookingStatus.CONFIRMED,
                BOOKED_AT,
                new BigDecimal("142.00"),
                "window preferred",
                "auth|owner-1",
                passengers,
                new BookingContactResponse("Praveen S", "praveen@example.com", "+447700900000"),
                new BookingPaymentResponse(PaymentStatus.PAID, new BigDecimal("142.00"), "GBP",
                        "PAY-2026-K7M4Z9", BOOKED_AT),
                tickets,
                "system", "system", 1L, BOOKED_AT, BOOKED_AT);
    }

    private static BookingResponse oneWayBooking() {
        return booking(List.of(passenger(11L, 0, 9L, "Ann", "Blake", "12A")),
                List.of(new BookingSegmentResponse(1L, 0, 9L, "UPCOMING")),
                List.of());
    }

    private static BookingResponse roundTripBooking() {
        return booking(
                List.of(passenger(11L, 0, 9L, "Ann", "Blake", "12A"),
                        passenger(12L, 1, 10L, "Ann", "Blake", "14C")),
                List.of(new BookingSegmentResponse(1L, 0, 9L, "UPCOMING"),
                        new BookingSegmentResponse(2L, 1, 10L, "UPCOMING")),
                List.of(ticket("1250000000011", 11L, 12L)));
    }

    @Nested
    @DisplayName("CREATED - the pre-payment mail")
    class Created {

        @Test
        @DisplayName("the subject and body name the PNR and ask for payment")
        void subjectAndBodyNameThePnrAndAskForPayment() {
            producer.publishBookingCreated(oneWayBooking(), List.of(outboundFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getType()).isEqualTo(BookingEventType.CREATED);
            assertThat(event.getSubject()).isEqualTo("Your SkyBook booking SB1234 has been created");
            assertThat(event.getMessage()).contains("SB1234", "Complete payment to confirm");
            assertThat(event.getContactEmail()).isEqualTo("praveen@example.com");
            assertThat(event.getContactName()).isEqualTo("Praveen S");
        }

        @Test
        @DisplayName("payment-service's correlation keys and the owner ride the event")
        void correlationKeysAndOwnerRideTheEvent() {
            producer.publishBookingCreated(oneWayBooking(), List.of(outboundFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getBookingId()).isEqualTo(77L);
            assertThat(event.getBookingReference()).isEqualTo("SB1234");
            assertThat(event.getBookingStatus()).isEqualTo("CONFIRMED");
            assertThat(event.getOwnerSubject()).isEqualTo("auth|owner-1");
            assertThat(event.getFlightId()).isEqualTo(9L);
            assertThat(event.getTotalFare()).isEqualByComparingTo("142.00");
            assertThat(event.getCurrency()).isEqualTo("GBP");
            assertThat(event.getPaymentStatus()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("timestamps are pre-formatted for the email template, seconds dropped")
        void timestampsArePreFormattedForTheTemplate() {
            producer.publishBookingCreated(oneWayBooking(), List.of(outboundFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getBookingDate()).isEqualTo(BOOKED_AT.toLocalDate() + " 14:05");
            assertThat(event.getDepartureTime()).isEqualTo(OUTBOUND_DEPARTS.toLocalDate() + " 09:25");
        }

        @Test
        @DisplayName("the booking's own flight fills the deprecated top-level mirror")
        void theBookingsOwnFlightFillsTheTopLevelMirror() {
            producer.publishBookingCreated(oneWayBooking(), List.of(outboundFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getFlightNumber()).isEqualTo("SB101");
            assertThat(event.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(event.getDestinationAirportCode()).isEqualTo("JFK");
            // 09:25 + 7h40 lands at 17:05 the same day.
            assertThat(event.getArrivalTime()).isEqualTo(OUTBOUND_ARRIVES.toLocalDate() + " 17:05");
        }

        @Test
        @DisplayName("before ticketing no passenger carries a ticket number")
        void beforeTicketingNoPassengerCarriesATicketNumber() {
            producer.publishBookingCreated(oneWayBooking(), List.of(outboundFlight()));

            assertThat(captureSentEvent().getPassengers())
                    .singleElement()
                    .satisfies(p -> {
                        assertThat(p.getName()).isEqualTo("Ann Blake");
                        assertThat(p.getTicketNumber()).isNull();
                    });
        }

        @Test
        @DisplayName("a booking with no contact on file publishes nothing at all")
        void aBookingWithoutAContactPublishesNothing() {
            BookingResponse noContact = new BookingResponse(
                    77L, "SB1234", 500L, 9L, List.of(), BookingStatus.CREATED, BOOKED_AT,
                    new BigDecimal("142.00"), null, "auth|owner-1", List.of(),
                    null, null, List.of(), "system", "system", 1L, BOOKED_AT, BOOKED_AT);

            producer.publishBookingCreated(noContact, List.of(outboundFlight()));

            verifyNoInteractions(kafkaTemplate);
        }
    }

    @Nested
    @DisplayName("segment enrichment")
    class Segments {

        @Test
        @DisplayName("each leg carries only its own passengers and its own flight's details")
        void eachLegCarriesOnlyItsOwnPassengersAndFlight() {
            producer.publishBookingConfirmed(roundTripBooking(),
                    List.of(outboundFlight(), returnFlight()));

            List<BookingEventSegment> segments = captureSentEvent().getSegments();
            assertThat(segments).hasSize(2);

            BookingEventSegment outbound = segments.get(0);
            assertThat(outbound.getSegmentIndex()).isZero();
            assertThat(outbound.getFlightNumber()).isEqualTo("SB101");
            assertThat(outbound.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(outbound.getDepartureTerminal()).isEqualTo("T5");
            assertThat(outbound.getArrivalTerminal()).isEqualTo("T7");
            assertThat(outbound.getPassengers()).singleElement()
                    .extracting(BookingEventPassenger::getSeatNumber).isEqualTo("12A");

            BookingEventSegment inbound = segments.get(1);
            assertThat(inbound.getFlightNumber()).isEqualTo("SB102");
            assertThat(inbound.getOriginAirportCode()).isEqualTo("JFK");
            assertThat(inbound.getDepartureTime()).isEqualTo(RETURN_DEPARTS.toLocalDate() + " 18:00");
            assertThat(inbound.getPassengers()).singleElement()
                    .extracting(BookingEventPassenger::getSeatNumber).isEqualTo("14C");
        }

        @Test
        @DisplayName("a leg whose flight could not be fetched still ships, just without route details")
        void aLegWithoutFlightDetailsStillShips() {
            // flight-service was degraded: the facade passes what it has.
            producer.publishBookingConfirmed(roundTripBooking(), List.of(outboundFlight()));

            List<BookingEventSegment> segments = captureSentEvent().getSegments();
            assertThat(segments.get(1).getFlightId()).isEqualTo(10L);
            assertThat(segments.get(1).getFlightNumber()).isNull();
            assertThat(segments.get(1).getDepartureTime()).isNull();
            assertThat(segments.get(1).getArrivalTerminal()).isNull();
            // The passengers on that leg are still announced.
            assertThat(segments.get(1).getPassengers()).hasSize(1);
        }

        @Test
        @DisplayName("a null flight list leaves the route blank instead of failing the publish")
        void aNullFlightListLeavesTheRouteBlank() {
            producer.publishBookingCreated(oneWayBooking(), null);

            BookingEvent event = captureSentEvent();
            assertThat(event.getFlightNumber()).isNull();
            assertThat(event.getOriginAirportCode()).isNull();
            assertThat(event.getSegments()).singleElement()
                    .extracting(BookingEventSegment::getFlightNumber).isNull();
        }

        @Test
        @DisplayName("null entries in the flight list are skipped, the resolvable ones still enrich")
        void nullEntriesInTheFlightListAreSkipped() {
            producer.publishBookingConfirmed(roundTripBooking(),
                    Arrays.asList(outboundFlight(), null));

            BookingEvent event = captureSentEvent();
            assertThat(event.getFlightNumber()).isEqualTo("SB101");
            assertThat(event.getSegments().get(1).getFlightNumber()).isNull();
        }

        @Test
        @DisplayName("a booking with no segments sends null segments - the old-consumer marker")
        void aBookingWithNoSegmentsSendsNullSegments() {
            BookingResponse legacyShape = booking(
                    List.of(passenger(11L, 0, 9L, "Ann", "Blake", "12A")), List.of(), List.of());

            producer.publishBookingCreated(legacyShape, List.of(outboundFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getSegments()).isNull();
            assertThat(event.getPassengers()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("CONFIRMED - ticketing")
    class Confirmed {

        @Test
        @DisplayName("every row is stamped with the e-ticket number its coupon belongs to")
        void everyRowIsStampedWithItsTicketNumber() {
            producer.publishBookingConfirmed(roundTripBooking(),
                    List.of(outboundFlight(), returnFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getType()).isEqualTo(BookingEventType.CONFIRMED);
            assertThat(event.getPassengers())
                    .extracting(BookingEventPassenger::getTicketNumber)
                    .containsExactly("1250000000011", "1250000000011");
            assertThat(event.getSegments().get(1).getPassengers())
                    .singleElement()
                    .extracting(BookingEventPassenger::getTicketNumber).isEqualTo("1250000000011");
        }

        @Test
        @DisplayName("two travellers get their own ticket numbers, keyed by row not by order")
        void twoTravellersGetTheirOwnTicketNumbers() {
            BookingResponse twoPax = booking(
                    List.of(passenger(11L, 0, 9L, "Ann", "Blake", "12A"),
                            passenger(21L, 0, 9L, "Bob", "Blake", "12B")),
                    List.of(new BookingSegmentResponse(1L, 0, 9L, "UPCOMING")),
                    List.of(ticket("1250000000021", 21L), ticket("1250000000011", 11L)));

            producer.publishBookingConfirmed(twoPax, List.of(outboundFlight()));

            assertThat(captureSentEvent().getPassengers())
                    .extracting(BookingEventPassenger::getName, BookingEventPassenger::getTicketNumber)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Ann Blake", "1250000000011"),
                            org.assertj.core.groups.Tuple.tuple("Bob Blake", "1250000000021"));
        }

        @Test
        @DisplayName("the money and check-in fields check-in-service snapshots are copied per row")
        void theMoneyAndCheckInFieldsAreCopiedPerRow() {
            producer.publishBookingConfirmed(oneWayBooking(), List.of(outboundFlight()));

            assertThat(captureSentEvent().getPassengers()).singleElement().satisfies(p -> {
                assertThat(p.getBookingPassengerId()).isEqualTo(11L);
                assertThat(p.getSegmentIndex()).isZero();
                assertThat(p.getTravelClass()).isEqualTo("ECONOMY");
                assertThat(p.getFareType()).isEqualTo("SAVER");
                assertThat(p.getFare()).isEqualByComparingTo("142.00");
                assertThat(p.getSeatSurcharge()).isEqualByComparingTo("12.00");
                assertThat(p.getCurrency()).isEqualTo("GBP");
                assertThat(p.getCheckInStatus()).isEqualTo("NOT_OPEN");
            });
        }

        @Test
        @DisplayName("the confirmed mail says confirmed, not created")
        void theConfirmedMailSaysConfirmed() {
            producer.publishBookingConfirmed(oneWayBooking(), List.of(outboundFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getSubject()).isEqualTo("Your SkyBook booking SB1234 is confirmed");
            assertThat(event.getMessage()).contains("is confirmed", "Have a great flight");
        }
    }

    @Nested
    @DisplayName("CANCELLED - the refund tier rides the event")
    class Cancelled {

        @Test
        @DisplayName("a 100% tier promises the refund and tells payment-service to pay in full")
        void fullTierPromisesTheRefund() {
            producer.publishBookingCancelled(oneWayBooking(), List.of(outboundFlight()), 100, null);

            BookingEvent event = captureSentEvent();
            assertThat(event.getType()).isEqualTo(BookingEventType.CANCELLED);
            assertThat(event.getRefundTierPercent()).isEqualTo(100);
            assertThat(event.getSubject()).isEqualTo("Your SkyBook booking SB1234 has been cancelled");
            assertThat(event.getMessage()).contains("If a refund is due");
        }

        @Test
        @DisplayName("a 0% tier says no refund is due - payment-service must create none")
        void sameDayTierSaysNoRefundIsDue() {
            producer.publishBookingCancelled(oneWayBooking(), List.of(outboundFlight()), 0, null);

            BookingEvent event = captureSentEvent();
            assertThat(event.getRefundTierPercent()).isZero();
            assertThat(event.getMessage()).contains("same-day cancellation policy no refund is due");
        }

        @Test
        @DisplayName("a partial tier names the exact percent the passenger was quoted")
        void aPartialTierNamesTheExactPercent() {
            producer.publishBookingCancelled(oneWayBooking(), List.of(outboundFlight()), 50, null);

            BookingEvent event = captureSentEvent();
            assertThat(event.getRefundTierPercent()).isEqualTo(50);
            assertThat(event.getMessage()).contains("A 50% refund per your fare rules");
        }

        @Test
        @DisplayName("the fare breakdown rides along so only the unused legs are refunded")
        void theFareBreakdownRidesAlong() {
            producer.publishBookingCancelled(roundTripBooking(),
                    List.of(outboundFlight(), returnFlight()), 50, "FLEXI:100.00;SAVER:80.00");

            BookingEvent event = captureSentEvent();
            assertThat(event.getRefundBreakdown()).isEqualTo("FLEXI:100.00;SAVER:80.00");
            assertThat(event.getCancelledBookingPassengerIds()).isNull();
        }
    }

    @Nested
    @DisplayName("PARTIALLY_CANCELLED - money and rows for two consumers at once")
    class PartiallyCancelled {

        @Test
        @DisplayName("the mail names what went and the exact amount coming back")
        void theMailNamesWhatWentAndTheAmount() {
            producer.publishBookingPartiallyCancelled(roundTripBooking(),
                    List.of(outboundFlight(), returnFlight()), 100, "SAVER:142.00",
                    List.of(12L), "the return", new BigDecimal("142.00"));

            BookingEvent event = captureSentEvent();
            assertThat(event.getType()).isEqualTo(BookingEventType.PARTIALLY_CANCELLED);
            assertThat(event.getSubject()).isEqualTo("Your SkyBook booking SB1234 has been updated");
            assertThat(event.getMessage())
                    .contains("the return has been cancelled")
                    .contains("The rest of the booking is unchanged")
                    .contains("A refund of 142.00 GBP");
        }

        @Test
        @DisplayName("check-in-service is told exactly which rows to close")
        void checkInServiceIsToldWhichRowsToClose() {
            producer.publishBookingPartiallyCancelled(roundTripBooking(),
                    List.of(outboundFlight(), returnFlight()), 50, "SAVER:142.00",
                    List.of(11L, 12L), "2 passengers", new BigDecimal("71.00"));

            BookingEvent event = captureSentEvent();
            assertThat(event.getCancelledBookingPassengerIds()).containsExactly(11L, 12L);
            assertThat(event.getRefundBreakdown()).isEqualTo("SAVER:142.00");
            assertThat(event.getRefundTierPercent()).isEqualTo(50);
        }

        @Test
        @DisplayName("a zero tier promises nothing even if a breakdown was computed")
        void aZeroTierPromisesNothing() {
            producer.publishBookingPartiallyCancelled(roundTripBooking(),
                    List.of(outboundFlight()), 0, "SAVER:142.00",
                    List.of(12L), "the return", new BigDecimal("142.00"));

            assertThat(captureSentEvent().getMessage())
                    .contains("no refund is due for this change")
                    .doesNotContain("A refund of");
        }

        @Test
        @DisplayName("a null refund amount promises nothing - the facade could not price it")
        void aNullRefundAmountPromisesNothing() {
            producer.publishBookingPartiallyCancelled(roundTripBooking(),
                    List.of(outboundFlight()), 100, null, List.of(12L), "the return", null);

            assertThat(captureSentEvent().getMessage()).contains("no refund is due for this change");
        }

        @Test
        @DisplayName("a refund that computes to zero promises nothing either")
        void aZeroRefundAmountPromisesNothing() {
            producer.publishBookingPartiallyCancelled(roundTripBooking(),
                    List.of(outboundFlight()), 100, "SAVER:0.00", List.of(12L), "the return",
                    new BigDecimal("0.00"));

            assertThat(captureSentEvent().getMessage()).contains("no refund is due for this change");
        }
    }

    @Nested
    @DisplayName("FARE_ALERT - a mail with no booking behind it")
    class FareAlert {

        @Test
        @DisplayName("the event carries only the recipient and the copy, and is typed so others ignore it")
        void theEventCarriesOnlyTheRecipientAndTheCopy() {
            producer.publishFareAlert("watcher@example.com", "Fare update: LHR to JFK",
                    "The Economy fare has dropped: GBP 120.00 is now GBP 99.00.");

            BookingEvent event = captureSentEvent();
            assertThat(event.getType()).isEqualTo(BookingEventType.FARE_ALERT);
            assertThat(event.getContactEmail()).isEqualTo("watcher@example.com");
            assertThat(event.getContactName()).isEqualTo("traveller");
            assertThat(event.getSubject()).isEqualTo("Fare update: LHR to JFK");
            assertThat(event.getMessage()).contains("is now GBP 99.00");
            // Nothing booking-shaped: payment and check-in have no work to do.
            assertThat(event.getBookingReference()).isNull();
            assertThat(event.getBookingId()).isNull();
            assertThat(event.getPassengers()).isNull();
            assertThat(event.getSegments()).isNull();
        }
    }

    @Nested
    @DisplayName("degraded inputs and broker failures")
    class Degraded {

        @Test
        @DisplayName("an unpaid booking sends null currency and payment status rather than failing")
        void anUnpaidBookingSendsNullMoneyFields() {
            BookingResponse unpaid = new BookingResponse(
                    77L, "SB1234", 500L, 9L,
                    List.of(new BookingSegmentResponse(1L, 0, 9L, "UPCOMING")),
                    null, null, new BigDecimal("142.00"), null, null,
                    null,
                    new BookingContactResponse("Praveen S", "praveen@example.com", null),
                    null, null, "system", "system", 1L, BOOKED_AT, BOOKED_AT);

            producer.publishBookingCreated(unpaid, List.of(outboundFlight()));

            BookingEvent event = captureSentEvent();
            assertThat(event.getCurrency()).isNull();
            assertThat(event.getPaymentStatus()).isNull();
            assertThat(event.getBookingStatus()).isNull();
            assertThat(event.getBookingDate()).isNull();
            assertThat(event.getPassengers()).isNull();
            assertThat(event.getOwnerSubject()).isNull();
            // The leg still ships, just with no passengers under it.
            assertThat(event.getSegments()).singleElement()
                    .extracting(BookingEventSegment::getPassengers)
                    .isEqualTo(List.of());
        }

        @Test
        @DisplayName("a broker rejection is logged, never thrown at the caller mid-booking")
        void aBrokerRejectionIsNeverThrownAtTheCaller() {
            when(kafkaTemplate.send(anyString(), any(BookingEvent.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

            assertThatCode(() -> producer.publishBookingCreated(oneWayBooking(), List.of(outboundFlight())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a broker rejection on a fare alert is swallowed the same way")
        void aBrokerRejectionOnAFareAlertIsSwallowed() {
            when(kafkaTemplate.send(anyString(), any(BookingEvent.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

            assertThatCode(() -> producer.publishFareAlert("watcher@example.com", "subject", "body"))
                    .doesNotThrowAnyException();
        }
    }
}
