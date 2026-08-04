package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventSegment;
import com.skybook.praveen.common.event.BookingEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The e-ticket is a legal-ish travel document, so these assert the markup it
 * carries - coupon per leg, e-ticket number, fares - rather than only that a
 * PDF came out. The markup is checked as a string because the PDF text layer
 * loses the structure; one case still goes through the real renderer, since
 * openhtmltopdf parses with an XML parser and an unclosed tag added later
 * would blow up at send-time rather than here.
 */
class TicketPdfTemplateTest {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final LocalDateTime DEPARTURE =
            LocalDateTime.now().plusDays(30).withHour(9).withMinute(15).withSecond(0).withNano(0);

    private final TicketPdfTemplate template = new TicketPdfTemplate();

    private static BookingEventPassenger passenger(String name, String ticketNumber, String seat) {
        return passenger(name, ticketNumber, seat, "450.00");
    }

    /** A coupon of a traveller's ticket - on a round trip each leg carries its own share of the fare. */
    private static BookingEventPassenger passenger(String name, String ticketNumber, String seat, String fare) {
        return BookingEventPassenger.builder()
                .name(name)
                .ticketNumber(ticketNumber)
                .seatNumber(seat)
                .travelClass("ECONOMY")
                .fareType("FLEXI")
                .fare(new BigDecimal(fare))
                .checkInStatus("NOT_OPEN")
                .build();
    }

    private static BookingEvent.BookingEventBuilder ticket() {
        return BookingEvent.builder()
                .type(BookingEventType.CONFIRMED)
                .bookingReference("SB8U33")
                .contactName("Praveen Somireddy")
                .bookingDate(EVENT_TIME.format(DEPARTURE.minusDays(40)))
                .flightNumber("BA1442")
                .originAirportCode("LHR")
                .destinationAirportCode("EDI")
                .departureTime(EVENT_TIME.format(DEPARTURE))
                .arrivalTime(EVENT_TIME.format(DEPARTURE.plusHours(1).plusMinutes(25)))
                .totalFare(new BigDecimal("450.00"))
                .currency("GBP")
                .paymentStatus("PAID")
                .passengers(List.of(passenger("Praveen Somireddy", "1251234567890", "12B")));
    }

    private static BookingEventSegment.BookingEventSegmentBuilder segment(
            Integer index, String flightNumber, String origin, String destination) {
        return BookingEventSegment.builder()
                .segmentIndex(index)
                .flightNumber(flightNumber)
                .originAirportCode(origin)
                .destinationAirportCode(destination)
                .departureTime(EVENT_TIME.format(DEPARTURE))
                .arrivalTime(EVENT_TIME.format(DEPARTURE.plusHours(1).plusMinutes(25)));
    }

    @Nested
    @DisplayName("Single-leg ticket")
    class SingleLegTicket {

        @Test
        void carriesThePnrBookingDateRouteAndTraveller() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains(">SB8U33</div>");
            assertThat(xhtml).contains("Booked " + EVENT_TIME.format(DEPARTURE.minusDays(40)));
            assertThat(xhtml).contains(">LHR</div>");
            assertThat(xhtml).contains(">London</div>");
            assertThat(xhtml).contains(">EDI</div>");
            assertThat(xhtml).contains("Flight BA1442");
            assertThat(xhtml).contains("Departs<br/>" + EVENT_TIME.format(DEPARTURE));
            assertThat(xhtml).contains("Praveen Somireddy");
            assertThat(xhtml).contains(">&#163;450.00</div>");
            assertThat(xhtml).contains("color:#1a7f37;\">PAID</span>");
        }

        @Test
        void printsTheThirteenDigitTicketNumberInItsDisplayedForm() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains("125-1234567890");
        }

        @Test
        void aTravellerWithoutATicketNumberYetShowsADash() {
            String xhtml = template.render(ticket()
                    .passengers(List.of(passenger("Praveen Somireddy", null, "12B")))
                    .build(), null);

            assertThat(xhtml).doesNotContain("125-");
            assertThat(xhtml).doesNotContain("null");
        }

        @Test
        void aTicketNumberTooShortToSplitIsPrintedAsIs() {
            String xhtml = template.render(ticket()
                    .passengers(List.of(passenger("Praveen Somireddy", "125", "12B")))
                    .build(), null);

            assertThat(xhtml).contains(">125</td>");
        }

        @Test
        void realTerminalsRideBothEndsOfTheRoute() {
            String xhtml = template.render(ticket()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI")
                            .departureTerminal("5")
                            .arrivalTerminal("1")
                            .build()))
                    .build(), null);

            assertThat(xhtml).contains("London - Terminal 5");
            assertThat(xhtml).contains("- Terminal 1");
        }

        @Test
        void aTicketWithoutARouteOrTimesPrintsDashesRatherThanNulls() {
            String xhtml = template.render(ticket()
                    .bookingReference(null)
                    .bookingDate(null)
                    .flightNumber(null)
                    .originAirportCode(null)
                    .destinationAirportCode(null)
                    .departureTime(null)
                    .arrivalTime(null)
                    .totalFare(null)
                    .paymentStatus(null)
                    .passengers(null)
                    .build(), null);

            assertThat(xhtml).doesNotContain("null");
            assertThat(xhtml).contains("Flight -");
            assertThat(xhtml).contains("Departs<br/>-");
            assertThat(xhtml).contains("color:#b45309;\">PENDING</span>");
        }

        @Test
        void blankAndAbsentTravellerFieldsAreTreatedAsMissingRatherThanPrinted() {
            String xhtml = template.render(ticket()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI")
                            .departureTerminal("  ")
                            .arrivalTerminal("")
                            .build()))
                    .passengers(List.of(BookingEventPassenger.builder()
                            .ticketNumber("   ")
                            .seatNumber("  ")
                            .build()))
                    .build(), null);

            assertThat(xhtml).doesNotContain("Terminal");
            assertThat(xhtml).doesNotContain("null");
            assertThat(xhtml).contains("text-align:center;\">-</td>");
        }

        @Test
        void everyCurrencyGetsItsOwnPrefix() {
            assertThat(template.render(ticket().currency("USD").build(), null))
                    .contains(">US$450.00</div>");
            assertThat(template.render(ticket().currency("EUR").build(), null))
                    .contains(">EUR 450.00</div>");
            assertThat(template.render(ticket().currency(null).build(), null))
                    .contains(">450.00</div>");
        }

        @Test
        void theQrIsEmbeddedAsADataUriOrOmittedEntirely() {
            String withQr = template.render(ticket().build(), new byte[]{1, 2, 3});
            String withoutQr = template.render(ticket().build(), null);

            assertThat(withQr).contains("<img src=\"data:image/png;base64,AQID\" width=\"110\" height=\"110\"");
            assertThat(withoutQr).doesNotContain("<img");
            assertThat(withoutQr).contains("Present this QR code at check-in");
        }

        @Test
        void travellerSuppliedMarkupIsEscaped() {
            String xhtml = template.render(ticket()
                    .passengers(List.of(passenger("<script>alert('pwn')</script>", "1251234567890", "12B")))
                    .build(), null);

            assertThat(xhtml).doesNotContain("<script>");
            assertThat(xhtml).contains("&lt;script&gt;alert('pwn')&lt;/script&gt;");
        }
    }

    @Nested
    @DisplayName("Multi-leg ticket")
    class MultiLegTicket {

        private BookingEvent roundTrip() {
            return ticket()
                    .segments(List.of(
                            segment(0, "BA1442", "LHR", "EDI")
                                    .passengers(List.of(passenger(
                                            "Praveen Somireddy", "1251234567890", "12B", "225.00")))
                                    .build(),
                            segment(1, "BA1451", "EDI", "LHR")
                                    .passengers(List.of(passenger(
                                            "Praveen Somireddy", "1251234567890", "4A", "225.00")))
                                    .build()))
                    .build();
        }

        @Test
        void oneCouponHeaderPerLegOverTheLegsOwnTravellerRows() {
            String xhtml = template.render(roundTrip(), null);

            assertThat(xhtml).contains("Coupon 1 &#8226; LHR &#8594; EDI (Outbound)");
            assertThat(xhtml).contains("Coupon 2 &#8226; EDI &#8594; LHR (Return)");
            assertThat(xhtml).contains(">12B</td>");
            assertThat(xhtml).contains(">4A</td>");
        }

        @Test
        void oneLabelledRouteTablePerLeg() {
            String xhtml = template.render(roundTrip(), null);

            assertThat(xhtml).contains("Outbound - Flight BA1442");
            assertThat(xhtml).contains("Return - Flight BA1451");
        }

        @Test
        void aThirdLegIsNumberedRatherThanNamed() {
            String xhtml = template.render(ticket()
                    .segments(List.of(
                            segment(0, "BA1442", "LHR", "EDI").build(),
                            segment(1, "BA1451", "EDI", "LHR").build(),
                            segment(2, "BA1460", "LHR", "GLA").build()))
                    .build(), null);

            assertThat(xhtml).contains("Coupon 3 &#8226; LHR &#8594; GLA (Leg 3)");
            assertThat(xhtml).contains("Leg 3 - Flight BA1460");
        }

        @Test
        void aLegWithoutAnIndexOrFlightNumberStillCoupons() {
            String xhtml = template.render(ticket()
                    .segments(List.of(
                            segment(null, null, null, null).build(),
                            segment(1, "BA1451", "EDI", "LHR").build()))
                    .build(), null);

            assertThat(xhtml).contains("Coupon 1 &#8226; ? &#8594; ? (Outbound)");
            assertThat(xhtml).contains("Outbound - Flight -");
            assertThat(xhtml).doesNotContain("null");
        }

        @Test
        void anEmptySegmentListFallsBackToTheTopLevelFlightFields() {
            String xhtml = template.render(ticket().segments(List.of()).build(), null);

            assertThat(xhtml).contains("Flight BA1442");
            assertThat(xhtml).doesNotContain("Coupon");
        }

        @Test
        void aSingleSegmentJourneyKeepsTheFlatTravellerListAndDropsTheCoupons() {
            String xhtml = template.render(ticket()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI").build()))
                    .build(), null);

            assertThat(xhtml).doesNotContain("Coupon");
            assertThat(xhtml).contains("Flight BA1442");
            assertThat(xhtml).contains(">12B</td>");
        }

        @Test
        void theMultiLegMarkupIsStillWellFormedXhtmlTheRendererAccepts() {
            byte[] qr = new QrCodeGenerator().generatePng("SKYBOOK|SB8U33|FLIGHT 1|Praveen Somireddy", 280);

            byte[] pdf = new TicketPdfRenderer().render(template.render(roundTrip(), qr));

            assertThat(pdf).isNotEmpty();
            assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        }
    }
}
