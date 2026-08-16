package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventSegment;
import com.skybook.praveen.common.event.BookingEventType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emailed ticket is the Style C ticket-office ledger - the SAME design
 * as the frontend download (printable.ts). These assert the ledger's load-
 * bearing structure (bands, dot-leader fare lines, LASTNAME/FIRSTNAME rows,
 * per-leg coupons) on the markup, plus one pass through the REAL renderer
 * with PDFBox text extraction, so an unclosed tag or a dropped glyph fails
 * here rather than in a mailbox.
 */
class TicketPdfTemplateTest {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final LocalDateTime DEPARTURE =
            LocalDateTime.now().plusDays(30).withHour(9).withMinute(15).withSecond(0).withNano(0);

    private final TicketPdfTemplate template = new TicketPdfTemplate();

    private static BookingEventPassenger.BookingEventPassengerBuilder passenger(
            String name, String ticketNumber, String seat, String fare) {
        return BookingEventPassenger.builder()
                .name(name)
                .ticketNumber(ticketNumber)
                .segmentIndex(0)
                .seatNumber(seat)
                .travelClass("ECONOMY")
                .fareType("FLEXI")
                .fare(new BigDecimal(fare))
                .baseFare(new BigDecimal(fare))
                .checkInStatus("NOT_OPEN");
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

    private static BookingEvent.BookingEventBuilder ticket() {
        return BookingEvent.builder()
                .type(BookingEventType.CONFIRMED)
                .bookingReference("SB8U33")
                .contactName("Praveen Somireddy")
                .bookingDate(EVENT_TIME.format(DEPARTURE.minusDays(40)))
                .totalFare(new BigDecimal("450.00"))
                .currency("GBP")
                .paymentStatus("PAID")
                .segments(List.of(segment(0, "BA1442", "LHR", "EDI")
                        .passengers(List.of(passenger("Praveen Somireddy", "1251234567890", "12B", "450.00").build()))
                        .build()));
    }

    @Nested
    @DisplayName("The ledger structure")
    class LedgerStructure {

        @Test
        void carriesEveryStyleCBand() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains("Going places together");
            assertThat(xhtml).contains("ELECTRONIC TICKET RECEIPT");
            assertThat(xhtml).contains("PASSENGER(S)");
            assertThat(xhtml).contains("FARE CALCULATION");
            assertThat(xhtml).contains("SB8U33");
        }

        @Test
        void theCarrierContactBlockIsAlwaysPresent() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains("SkyBook Airways &#183; Contact");
            assertThat(xhtml).contains("+44 20 7946 0958");
            assertThat(xhtml).contains("support@skybook.example");
            assertThat(xhtml).contains("Registered office: SkyBook Airways Ltd");
        }

        @Test
        void fareLinesUseDotLeadersRouteAndPoundAmounts() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains("FARE");
            assertThat(xhtml).contains("LHR-EDI 1 X £450.00");
            assertThat(xhtml).contains("....");
            assertThat(xhtml).contains("TAX");
            assertThat(xhtml).contains("INCLUDED");
            assertThat(xhtml).contains("TOTAL");
            assertThat(xhtml).contains("PAID (INCL. ALL TAXES)");
            assertThat(xhtml).contains("£450.00");
        }

        @Test
        void passengerRowsFlipToLedgerNameOrder() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains("SOMIREDDY/PRAVEEN");
            assertThat(xhtml).contains("125-1234567890");
        }

        @Test
        void aChosenSeatOnANonSaverFareReadsWaived() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains("12B (WAIVED)");
            assertThat(xhtml).contains("£0.00");
        }

        @Test
        void extraBagsAndTheirFeesLandOnTheBagsLine() {
            String xhtml = template.render(ticket()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI")
                            .passengers(List.of(passenger("Praveen Somireddy", "1251234567890", "12B", "530.00")
                                    .baseFare(new BigDecimal("450.00"))
                                    .baggageFee(new BigDecimal("80.00"))
                                    .extraBags(2)
                                    .build()))
                            .build()))
                    .build(), null);

            assertThat(xhtml).contains("2 EXTRA");
            assertThat(xhtml).contains("£80.00");
        }

        @Test
        void taxesItemisePerCodeWhenTheBookingCarriesABreakdown() {
            String xhtml = template.render(ticket()
                    .taxTotal(new BigDecimal("261.40"))
                    .taxBreakdown("GB:216.00;UB:29.10;AE:16.30")
                    .build(), null);

            assertThat(xhtml).contains("TAXES");
            assertThat(xhtml).contains("UK AIR PASSENGER DUTY");
            assertThat(xhtml).contains("£216.00");
            assertThat(xhtml).contains("UK PASSENGER SERVICE CHARGE");
            assertThat(xhtml).contains("UAE PASSENGER FACILITY CHARGE");
            assertThat(xhtml).contains("£16.30");
            assertThat(xhtml).doesNotContain("INCLUDED");
        }

        @Test
        void preTaxationBookingsKeepTheLegacyTaxLine() {
            String xhtml = template.render(ticket().build(), null);

            assertThat(xhtml).contains("INCLUDED");
        }

        @Test
        void theBarcodeEncodesThePnrWithAHumanReadableLine() {
            String xhtml = template.render(ticket().build(), null);

            // Deterministic bar cells plus the spaced PNR beneath - same
            // algorithm as the frontend's barcodeSvg.
            assertThat(xhtml).contains("background-color:#0f172a");
            assertThat(xhtml).contains("S&#160;&#160;B&#160;&#160;8");
        }
    }

    @Nested
    @DisplayName("Multi-leg tickets")
    class MultiLeg {

        private BookingEvent roundTrip() {
            return ticket()
                    .totalFare(new BigDecimal("900.00"))
                    .segments(List.of(
                            segment(0, "BA1442", "LHR", "EDI")
                                    .departureTerminal("5")
                                    .passengers(List.of(passenger("Praveen Somireddy", "1251234567890", "12B", "450.00").build()))
                                    .build(),
                            segment(1, "BA1443", "EDI", "LHR")
                                    .arrivalTerminal("5")
                                    .passengers(List.of(passenger("Praveen Somireddy", "1251234567890", "14C", "450.00")
                                            .segmentIndex(1).build()))
                                    .build()))
                    .build();
        }

        @Test
        void eachLegGetsItsOwnLabelledCouponAndFareLine() {
            String xhtml = template.render(roundTrip(), null);

            assertThat(xhtml).contains("OUTBOUND");
            assertThat(xhtml).contains("RETURN");
            assertThat(xhtml).contains("LHR-EDI 1 X £450.00");
            assertThat(xhtml).contains("EDI-LHR 1 X £450.00");
            assertThat(xhtml).contains("SEATS OUT / RET");
            assertThat(xhtml).contains("12B / 14C");
            assertThat(xhtml).contains("£900.00");
        }

        @Test
        void chainedLegsPrintTheirConnectionTimeButAReturnDaysLaterDoesNot() {
            // Through-ticket: LHR-DXB lands 19:05, DXB-HYD leaves 21:45 - a
            // 2h40 connection band. The ROUND TRIP fixture's 13-day gap to the
            // return must print nothing.
            String through = template.render(ticket()
                    .segments(List.of(
                            segment(0, "EK030", "LHR", "DXB")
                                    .departureTime("2026-08-22 08:45").arrivalTime("2026-08-22 19:05")
                                    .passengers(List.of(passenger("Praveenreddy Somireddy", "1250000027001", "1B", "833.00").build()))
                                    .build(),
                            segment(1, "EK526", "DXB", "HYD")
                                    .departureTime("2026-08-22 21:45").arrivalTime("2026-08-23 03:05")
                                    .passengers(List.of(passenger("Praveenreddy Somireddy", "1250000027001", "1B", "833.00")
                                            .segmentIndex(1).build()))
                                    .build()))
                    .build(), null);

            assertThat(through).contains("CONNECTION IN DUBAI (DXB)");
            assertThat(through).contains("2h 40m");
            assertThat(through).contains("CONNECTING FLIGHT");

            assertThat(template.render(roundTrip(), null)).doesNotContain("CONNECTION IN");
        }

        @Test
        void terminalsRideTheItineraryRows() {
            String xhtml = template.render(roundTrip(), null);

            assertThat(xhtml).contains("Terminal: <b>5</b>");
        }

        @Test
        void bothCouponsSumIntoOneFarePaidRowPerTraveller() {
            String xhtml = template.render(roundTrip(), null);

            // One PASSENGER(S) row (same ticket number), fare = both coupons.
            assertThat(xhtml.split("SOMIREDDY/PRAVEEN", -1)).hasSize(2);
            assertThat(xhtml).contains("£900.00");
        }
    }

    @Nested
    @DisplayName("Robustness")
    class Robustness {

        @Test
        void nullsNeverLeakIntoTheDocument() {
            String xhtml = template.render(ticket()
                    .bookingReference(null)
                    .bookingDate(null)
                    .totalFare(null)
                    .paymentStatus(null)
                    .segments(List.of(segment(0, null, null, null)
                            .departureTime(null)
                            .arrivalTime(null)
                            .passengers(List.of(BookingEventPassenger.builder().build()))
                            .build()))
                    .build(), null);

            assertThat(xhtml).doesNotContain("null");
        }

        @Test
        void travellerSuppliedMarkupIsEscaped() {
            String xhtml = template.render(ticket()
                    .segments(List.of(segment(0, "BA1442", "LHR", "EDI")
                            .passengers(List.of(passenger("<script>alert(1)</script>", null, "1A", "450.00").build()))
                            .build()))
                    .build(), null);

            assertThat(xhtml).doesNotContain("<script>");
            assertThat(xhtml).contains("&lt;script&gt;");
        }

        @Test
        void usdBookingsKeepTheirDollarPrefix() {
            String xhtml = template.render(ticket().currency("USD").build(), null);

            assertThat(xhtml).contains("US$450.00");
            assertThat(xhtml).doesNotContain("£");
        }

        @Test
        void vtzResolvesToItsCityName() {
            String xhtml = template.render(ticket()
                    .segments(List.of(segment(0, "6E562", "HYD", "VTZ")
                            .passengers(List.of(passenger("Praveen Somireddy", "1251234567890", "2A", "685.00").build()))
                            .build()))
                    .build(), null);

            assertThat(xhtml).contains("HYDERABAD");
            assertThat(xhtml).contains("VISAKHAPATNAM");
        }
    }

    @Test
    @DisplayName("renders through the real openhtmltopdf pipeline")
    void rendersThroughTheRealPipeline() throws IOException {
        TicketPdfRenderer renderer = new TicketPdfRenderer();

        byte[] pdf = renderer.render(template.render(ticket()
                .segments(List.of(segment(0, "6E562", "HYD", "VTZ")
                        .departureTerminal("1")
                        .arrivalTerminal("1")
                        .passengers(List.of(passenger("Praveen Somireddy", "1251234567890", "2A", "685.00").build()))
                        .build()))
                .totalFare(new BigDecimal("685.00"))
                .build(), null));

        assertThat(pdf).isNotEmpty();
        try (PDDocument document = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertThat(text).contains("ELECTRONIC TICKET RECEIPT");
            assertThat(text).contains("PASSENGER(S)");
            assertThat(text).contains("FARE CALCULATION");
            assertThat(text).contains("HYD-VTZ 1 X £685.00");
            assertThat(text).contains("SOMIREDDY/PRAVEEN");
            assertThat(text).contains("VISAKHAPATNAM");
        }
    }
}
