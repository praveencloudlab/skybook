package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventSegment;
import com.skybook.praveen.common.event.BookingEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The booking email is the only artefact most travellers ever see, so these
 * assert the rendered HTML itself - PNR, fares, route, clock times - not that
 * the method returned something. Two properties matter beyond the happy path:
 * traveller-supplied text must arrive escaped (names come straight from a
 * booking form), and unreadable times must degrade to a card without a
 * duration rather than throw on the Kafka listener thread and poison the
 * consumer group.
 */
class BookingEmailTemplateTest {

    /** The template's own placeholders, spelled out so this source stays ASCII. */
    private static final String EM_DASH = "—";
    private static final String MIDDOT = "·";

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Anchored to now so the fixture never expires, with the clock pinned so the
     * derived "check-in opens 24h before" string stays stable.
     */
    private static final LocalDateTime DEPARTURE =
            LocalDateTime.now().plusDays(30).withHour(9).withMinute(15).withSecond(0).withNano(0);

    private static final LocalDateTime ARRIVAL = DEPARTURE.plusHours(1).plusMinutes(25);

    private final BookingEmailTemplate template = new BookingEmailTemplate();

    private static BookingEventPassenger passenger(String name, String seat, String travelClass,
                                                   String fareType, String fare, String checkInStatus) {
        return BookingEventPassenger.builder()
                .name(name)
                .seatNumber(seat)
                .travelClass(travelClass)
                .fareType(fareType)
                .fare(fare == null ? null : new BigDecimal(fare))
                .checkInStatus(checkInStatus)
                .build();
    }

    /**
     * LHR -> EDI on purpose: both ends sit in one timezone, so every duration
     * asserted below is the elapsed time a traveller actually experiences
     * regardless of how the template arrives at it.
     */
    private static BookingEvent.BookingEventBuilder shortHaul() {
        return BookingEvent.builder()
                .type(BookingEventType.CONFIRMED)
                .bookingReference("SB8U33")
                .contactEmail("praveen.somireddy@gmail.com")
                .contactName("Praveen Somireddy")
                .subject("Your SkyBook booking is confirmed")
                .message("Your booking is confirmed.")
                .bookingDate(EVENT_TIME.format(DEPARTURE.minusDays(40)))
                .flightId(7L)
                .flightNumber("BA1442")
                .originAirportCode("LHR")
                .destinationAirportCode("EDI")
                .departureTime(EVENT_TIME.format(DEPARTURE))
                .arrivalTime(EVENT_TIME.format(ARRIVAL))
                .totalFare(new BigDecimal("450.00"))
                .currency("GBP")
                .paymentStatus("PAID")
                .passengers(List.of(
                        passenger("Praveen Somireddy", "12B", "ECONOMY", "FLEXI", "450.00", "NOT_OPEN")));
    }

    private static BookingEventSegment.BookingEventSegmentBuilder segment(
            Integer index, String flightNumber, String origin, String destination) {
        return BookingEventSegment.builder()
                .segmentIndex(index)
                .flightNumber(flightNumber)
                .originAirportCode(origin)
                .destinationAirportCode(destination)
                .departureTime(EVENT_TIME.format(DEPARTURE))
                .arrivalTime(EVENT_TIME.format(ARRIVAL));
    }

    @Nested
    @DisplayName("Status chip")
    class StatusChip {

        @Test
        void confirmedWearsTheGreenChip() {
            String html = template.render(shortHaul().type(BookingEventType.CONFIRMED).build());

            assertThat(html).contains("background:#dafbe1;color:#1a7f37;");
            assertThat(html).contains("Booking Confirmed");
        }

        @Test
        void completedSharesTheConfirmedGreen() {
            String html = template.render(shortHaul().type(BookingEventType.COMPLETED).build());

            assertThat(html).contains("background:#dafbe1;color:#1a7f37;");
            assertThat(html).contains("Booking Completed");
        }

        @Test
        void cancelledWearsTheRedChip() {
            String html = template.render(shortHaul().type(BookingEventType.CANCELLED).build());

            assertThat(html).contains("background:#ffe5e0;color:#b42318;");
            assertThat(html).contains("Booking Cancelled");
        }

        @Test
        void expiredSharesTheCancelledRed() {
            String html = template.render(shortHaul().type(BookingEventType.EXPIRED).build());

            assertThat(html).contains("background:#ffe5e0;color:#b42318;");
            assertThat(html).contains("Booking Expired");
        }

        @Test
        void createdAwaitingPaymentWearsTheAmberChip() {
            String html = template.render(shortHaul().type(BookingEventType.CREATED).build());

            assertThat(html).contains("background:#fff8e6;color:#b45309;");
            assertThat(html).contains("Booking Created");
        }

        @Test
        void aMultiWordTypeIsPrettyPrintedRatherThanShouted() {
            String html = template.render(shortHaul().type(BookingEventType.PARTIALLY_CANCELLED).build());

            assertThat(html).contains("Booking Partially cancelled");
            assertThat(html).doesNotContain("PARTIALLY_CANCELLED");
        }
    }

    @Nested
    @DisplayName("Header and totals")
    class HeaderAndTotals {

        @Test
        void showsThePnrFlightNumberBookingDateAndFooterReference() {
            String html = template.render(shortHaul().build());

            assertThat(html).contains("BOOKING REFERENCE (PNR)");
            assertThat(html).contains(">SB8U33</div>");
            assertThat(html).contains(">BA1442</div>");
            assertThat(html).contains("Booked " + EVENT_TIME.format(DEPARTURE.minusDays(40)));
            assertThat(html).contains("Reference SB8U33 " + MIDDOT + " Do not reply");
        }

        @Test
        void anEventWithoutAFlightNumberFallsBackToTheFlightId() {
            String html = template.render(shortHaul().flightNumber(null).build());

            assertThat(html).contains(">#7</div>");
        }

        @Test
        void anEventWithNeitherFlightNumberNorIdShowsADash() {
            String html = template.render(shortHaul().flightNumber(null).flightId(null).build());

            assertThat(html).contains(">" + EM_DASH + "</div>");
        }

        @Test
        void aMissingPnrAndBookingDateShowDashesInsteadOfNull() {
            String html = template.render(shortHaul().bookingReference(null).bookingDate(null).build());

            assertThat(html).contains("letter-spacing:2px;\">" + EM_DASH + "</div>");
            assertThat(html).contains("Booked " + EM_DASH);
            assertThat(html).doesNotContain("null");
        }

        @Test
        void greetsTheContactByNameAndCarriesTheProducersMessage() {
            String html = template.render(shortHaul().build());

            assertThat(html).contains("Hello Praveen Somireddy,");
            assertThat(html).contains("Your booking is confirmed.");
        }

        @Test
        void anAnonymousBookingIsGreetedAsATraveler() {
            String html = template.render(shortHaul().contactName(null).message(null).build());

            assertThat(html).contains("Hello traveler,");
            assertThat(html).contains("font-size:14px;\"></p>");
        }

        @Test
        void aPaidBookingShowsThePaidTotalInGreen() {
            String html = template.render(shortHaul().build());

            assertThat(html).contains(">&#163;450.00</span>");
            assertThat(html).contains("Payment: <b style=\"color:#1a7f37;\">PAID</b>");
        }

        @Test
        void aBookingWithNoPaymentYetReadsPendingInAmber() {
            String html = template.render(shortHaul().paymentStatus(null).build());

            assertThat(html).contains("Payment: <b style=\"color:#b45309;\">PENDING</b>");
        }

        @Test
        void everyCurrencyGetsItsOwnPrefixAndAnAbsentTotalADash() {
            assertThat(template.render(shortHaul().currency("USD").build()))
                    .contains(">US$450.00</span>");
            assertThat(template.render(shortHaul().currency("EUR").build()))
                    .contains(">EUR 450.00</span>");
            assertThat(template.render(shortHaul().currency(null).build()))
                    .contains(">450.00</span>");
            assertThat(template.render(shortHaul().totalFare(null).build()))
                    .contains(">" + EM_DASH + "</span>");
        }
    }

    @Nested
    @DisplayName("Route card")
    class RouteCard {

        @Test
        void showsBothAirportsTheirCitiesAndTheLocalClockTimes() {
            String html = template.render(shortHaul().build());

            assertThat(html).contains(">LHR</div>");
            assertThat(html).contains(">London</div>");
            assertThat(html).contains("Departs<br>" + EVENT_TIME.format(DEPARTURE));
            assertThat(html).contains(">EDI</div>");
            assertThat(html).contains("Arrives<br>" + EVENT_TIME.format(ARRIVAL));
            assertThat(html).contains("BA1442 &middot; Direct");
        }

        @Test
        void anAirportOutsideTheLookupRendersWithoutACityLine() {
            // EDI has no entry in AirportCityLookup - the line collapses to
            // empty rather than printing "null".
            String html = template.render(shortHaul().build());

            assertThat(html).contains("<div style=\"color:#57606a;font-size:12px;\"></div>");
            assertThat(html).doesNotContain("null");
        }

        @Test
        void rendersTheElapsedTimeBetweenTwoAirportsInOneTimezone() {
            String html = template.render(shortHaul().build());

            assertThat(html).contains(">1h 25m</div>");
        }

        @Test
        void aWholeNumberOfHoursDropsTheMinutes() {
            String html = template.render(shortHaul()
                    .arrivalTime(EVENT_TIME.format(DEPARTURE.plusHours(2)))
                    .build());

            assertThat(html).contains(">2h</div>");
        }

        @Test
        void advertisesWhenOnlineCheckInOpens() {
            String html = template.render(shortHaul().build());

            assertThat(html).contains("Online check-in opens 24 hours before departure");
            assertThat(html).contains("<b>" + EVENT_TIME.format(DEPARTURE.minusDays(1)) + "</b>");
        }

        @Test
        void secondsAndTheIsoTSeparatorAreBothAccepted() {
            String html = template.render(shortHaul()
                    .departureTime(EVENT_TIME.format(DEPARTURE) + ":42")
                    .arrivalTime(ARRIVAL.toString())
                    .build());

            assertThat(html).contains(">1h 25m</div>");
            assertThat(html).contains("Online check-in opens 24 hours before departure");
        }

        @Test
        void anUnreadableDepartureDegradesToTheFlightNumberAndDropsTheAdvisory() {
            String html = template.render(shortHaul().departureTime("not a timestamp").build());

            assertThat(html).contains("margin-bottom:5px;\">BA1442</div>");
            assertThat(html).doesNotContain("Online check-in opens");
            // The unreadable value is still shown rather than swallowed.
            assertThat(html).contains("Departs<br>not a timestamp");
        }

        @Test
        void anArrivalNotAfterDepartureLeavesNoDuration() {
            String html = template.render(shortHaul()
                    .arrivalTime(EVENT_TIME.format(DEPARTURE))
                    .build());

            assertThat(html).contains("margin-bottom:5px;\">BA1442</div>");
        }

        @Test
        void absentTimesShowDashesAndNoDuration() {
            String html = template.render(shortHaul().departureTime(null).arrivalTime(null).build());

            assertThat(html).contains("Departs<br>" + EM_DASH);
            assertThat(html).contains("Arrives<br>" + EM_DASH);
            assertThat(html).contains("margin-bottom:5px;\">BA1442</div>");
        }

        @Test
        void aBookingWithoutARouteSkipsTheCardButKeepsTheRestOfTheEmail() {
            String withoutOrigin = template.render(shortHaul().originAirportCode(null).build());
            String withoutDestination = template.render(shortHaul().destinationAirportCode(null).build());

            assertThat(withoutOrigin).doesNotContain("Departs");
            assertThat(withoutOrigin).doesNotContain("Arrives");
            assertThat(withoutOrigin).contains(">SB8U33</div>");
            assertThat(withoutDestination).doesNotContain("Departs");
            assertThat(withoutDestination).contains("Praveen Somireddy");
        }

        @Test
        void realTerminalsAppearOnBothCityLines() {
            String html = template.render(shortHaul()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI")
                            .departureTerminal("5")
                            .arrivalTerminal("1")
                            .build()))
                    .build());

            // Asserted on the terminal alone: how the city and terminal are
            // separated is presentation the template may still change.
            assertThat(html).contains("London");
            assertThat(html).contains("Terminal 5");
            assertThat(html).contains("Terminal 1");

            // The separator must reach the reader as a rendered dot. Escaping
            // the assembled string instead of its parts turns &middot; into
            // &amp;middot;, and the traveller sees the markup spelled out.
            assertThat(html).doesNotContain("&amp;middot;");
        }

        @Test
        void blankTerminalsAreTreatedAsAbsent() {
            String html = template.render(shortHaul()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI")
                            .departureTerminal("  ")
                            .arrivalTerminal("")
                            .build()))
                    .build());

            assertThat(html).doesNotContain("Terminal");
        }
    }

    @Nested
    @DisplayName("Passenger table")
    class PassengerTable {

        @Test
        void rendersOneRowPerPassengerWithSeatCabinFareTypeAndCheckInState() {
            String html = template.render(shortHaul()
                    .passengers(List.of(
                            passenger("Praveen Somireddy", "12B", "ECONOMY", "FLEXI", "450.00", "NOT_OPEN"),
                            passenger("Divya Gopu", "12C", "PREMIUM_ECONOMY", "SAVER", "610.50", "CHECKED_IN")))
                    .build());

            assertThat(html).contains("Praveen Somireddy");
            assertThat(html).contains("Divya Gopu");
            assertThat(html).contains("<b>12B</b>");
            assertThat(html).contains("<b>12C</b>");
            assertThat(html).contains("Economy " + MIDDOT + " Flexi");
            assertThat(html).contains("Premium economy " + MIDDOT + " Saver");
            assertThat(html).contains("Not open");
            assertThat(html).contains("Checked in");
            assertThat(html).contains("&#163;450.00");
            assertThat(html).contains("&#163;610.50");
        }

        @Test
        void anUnseatedPassengerShowsADashRatherThanNull() {
            String html = template.render(shortHaul()
                    .passengers(List.of(
                            passenger("Praveen Somireddy", null, "ECONOMY", "FLEXI", "450.00", null)))
                    .build());

            assertThat(html).contains("<b>" + EM_DASH + "</b>");
            // A passenger with no snapshot status is not yet check-in eligible.
            assertThat(html).contains("Not open");
        }

        @Test
        void aPassengerMissingCabinFareTypeAndFareStillRenders() {
            String html = template.render(shortHaul()
                    .passengers(List.of(passenger("Praveen Somireddy", "12B", null, null, null, "NOT_OPEN")))
                    .build());

            assertThat(html).contains(EM_DASH + " " + MIDDOT + " " + EM_DASH);
            assertThat(html).contains("text-align:right;\">" + EM_DASH + "</td>");
            assertThat(html).doesNotContain("null");
        }

        @Test
        void blankStringsFromTheProducerAreTreatedAsMissingRatherThanPrinted() {
            String html = template.render(shortHaul()
                    .contactName("   ")
                    .departureTime("  ")
                    .passengers(List.of(
                            passenger("Praveen Somireddy", "  ", "ECONOMY", "FLEXI", "450.00", " ")))
                    .build());

            assertThat(html).contains("Hello traveler,");
            assertThat(html).contains("<b>" + EM_DASH + "</b>");
            assertThat(html).contains("Not open");
            assertThat(html).doesNotContain("Online check-in opens");
        }

        @Test
        void aPassengerRowWithNoNameAtAllRendersEmptyRatherThanNull() {
            String html = template.render(shortHaul()
                    .passengers(List.of(passenger(null, "12B", "ECONOMY", "FLEXI", "450.00", "NOT_OPEN")))
                    .build());

            assertThat(html).contains("border-top:1px solid #e5e7eb;\"></td>");
            assertThat(html).contains("<b>12B</b>");
            assertThat(html).doesNotContain("null");
        }

        @Test
        void aLeanEventWithNoPassengersStillRendersTheTotals() {
            String html = template.render(shortHaul().passengers(null).build());

            assertThat(html).contains(">&#163;450.00</span>");
            assertThat(html).contains("NAME");
        }
    }

    @Nested
    @DisplayName("Multi-segment journeys")
    class MultiSegmentJourneys {

        private BookingEvent roundTrip() {
            return shortHaul()
                    .segments(List.of(
                            segment(0, "BA1442", "LHR", "EDI")
                                    .passengers(List.of(passenger(
                                            "Praveen Somireddy", "12B", "ECONOMY", "FLEXI", "225.00", "NOT_OPEN")))
                                    .build(),
                            segment(1, "BA1451", "EDI", "LHR")
                                    .passengers(List.of(passenger(
                                            "Praveen Somireddy", "4A", "ECONOMY", "FLEXI", "225.00", "NOT_OPEN")))
                                    .build()))
                    .build();
        }

        @Test
        void eachLegHeadsItsOwnPassengerRows() {
            String html = template.render(roundTrip());

            assertThat(html).contains("Outbound &middot; LHR &rarr; EDI");
            assertThat(html).contains("Return &middot; EDI &rarr; LHR");
            assertThat(html).contains("<b>12B</b>");
            assertThat(html).contains("<b>4A</b>");
        }

        @Test
        void eachLegGetsItsOwnLabelledRouteCard() {
            String html = template.render(roundTrip());

            assertThat(html).contains(">Outbound</td>");
            assertThat(html).contains(">Return</td>");
            // Each card names its own leg's flight, not the event's mirror.
            assertThat(html).contains("BA1442 &middot; Direct");
            assertThat(html).contains("BA1451 &middot; Direct");
        }

        @Test
        void aThirdLegIsNumberedRatherThanNamed() {
            String html = template.render(shortHaul()
                    .segments(List.of(
                            segment(0, "BA1442", "LHR", "EDI").build(),
                            segment(1, "BA1451", "EDI", "LHR").build(),
                            segment(2, "BA1460", "LHR", "GLA").build()))
                    .build());

            assertThat(html).contains("Leg 3 &middot; LHR &rarr; GLA");
            assertThat(html).contains(">Leg 3</td>");
        }

        @Test
        void aSegmentWithoutAnIndexIsTreatedAsTheOutbound() {
            String html = template.render(shortHaul()
                    .segments(List.of(
                            segment(null, "BA1442", "LHR", "EDI").build(),
                            segment(1, "BA1451", "EDI", "LHR").build()))
                    .build());

            assertThat(html).contains("Outbound &middot; LHR &rarr; EDI");
        }

        @Test
        void aLegWithNoPassengersOfItsOwnStillPrintsItsHeader() {
            String html = template.render(shortHaul()
                    .segments(List.of(
                            segment(0, "BA1442", "LHR", "EDI").build(),
                            segment(1, "BA1451", "EDI", "LHR").build()))
                    .build());

            assertThat(html).contains("Outbound &middot; LHR &rarr; EDI");
            assertThat(html).contains("Return &middot; EDI &rarr; LHR");
            // The top-level mirror must NOT leak in once segments are present.
            assertThat(html).doesNotContain("<b>12B</b>");
        }

        @Test
        void aLegMissingItsAirportCodesFallsBackToQuestionMarks() {
            String html = template.render(shortHaul()
                    .segments(List.of(
                            segment(0, "BA1442", null, null).build(),
                            segment(1, "BA1451", "EDI", "LHR").build()))
                    .build());

            assertThat(html).contains("Outbound &middot; ? &rarr; ?");
            // No route card can be drawn for a leg with no route.
            assertThat(html).doesNotContain(">Outbound</td>");
            assertThat(html).contains(">Return</td>");
        }

        @Test
        void anEmptySegmentListFallsBackToTheTopLevelFlightFields() {
            String html = template.render(shortHaul().segments(List.of()).build());

            assertThat(html).contains(">LHR</div>");
            assertThat(html).contains("BA1442 &middot; Direct");
            assertThat(html).doesNotContain("&rarr;");
        }

        @Test
        void aSingleSegmentJourneyKeepsTheFlatPassengerListAndDropsTheLegHeaders() {
            String html = template.render(shortHaul()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI").build()))
                    .build());

            assertThat(html).doesNotContain("&rarr;");
            assertThat(html).doesNotContain(">Outbound</td>");
            assertThat(html).contains("<b>12B</b>");
            assertThat(html).contains(">LHR</div>");
        }
    }

    @Nested
    @DisplayName("QR block")
    class QrBlock {

        @Test
        void theQrIsOmittedUnlessTheConsumerAsksForIt() {
            String html = template.render(shortHaul().build());

            assertThat(html).doesNotContain("cid:" + BookingEmailTemplate.QR_CID);
        }

        @Test
        void theQrReferencesTheContentIdTheConsumerAttachesUnder() {
            String html = template.render(shortHaul().build(), true);

            assertThat(html).contains("src=\"cid:" + BookingEmailTemplate.QR_CID + "\"");
            assertThat(html).contains("Show this QR code at check-in");
        }
    }

    @Nested
    @DisplayName("Hostile input")
    class HostileInput {

        @Test
        void aPassengerNameCarryingMarkupIsEscapedNotEmbedded() {
            String html = template.render(shortHaul()
                    .passengers(List.of(passenger(
                            "<script>alert('pwn')</script>", "1A", "ECONOMY", "SAVER", "10.00", "NOT_OPEN")))
                    .build());

            assertThat(html).doesNotContain("<script>");
            assertThat(html).contains("&lt;script&gt;alert('pwn')&lt;/script&gt;");
        }

        @Test
        void markupInTheContactNameAndMessageIsEscapedToo() {
            String html = template.render(shortHaul()
                    .contactName("<img src=x onerror=alert(1)>")
                    .message("<b>urgent</b>")
                    .build());

            assertThat(html).doesNotContain("<img src=x");
            assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
            assertThat(html).contains("&lt;b&gt;urgent&lt;/b&gt;");
        }

        @Test
        void anAmpersandInATravellerNameIsEscapedOnce() {
            String html = template.render(shortHaul().contactName("Smith & Sons Travel").build());

            assertThat(html).contains("Hello Smith &amp; Sons Travel,");
            assertThat(html).doesNotContain("Hello Smith & Sons");
        }

        @Test
        void markupInThePnrAndFlightNumberCannotBreakOutOfTheirCells() {
            String html = template.render(shortHaul()
                    .bookingReference("<b>SB8U33</b>")
                    .flightNumber("<i>BA1442</i>")
                    .build());

            assertThat(html).contains("&lt;b&gt;SB8U33&lt;/b&gt;");
            assertThat(html).contains("&lt;i&gt;BA1442&lt;/i&gt;");
            assertThat(html).doesNotContain("<b>SB8U33</b>");
        }
    }
}
