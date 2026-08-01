package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.common.event.CheckInEventType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emailed pass must match the frontend pass, value for value - these
 * render through the REAL openhtmltopdf pipeline and assert on the text PDFBox
 * extracts, so a template that stops parsing as XHTML or drops a field fails
 * here rather than in a mailbox.
 */
class BoardingPassPdfTemplateTest {

    private final BoardingPassPdfTemplate template = new BoardingPassPdfTemplate();
    private final TicketPdfRenderer renderer = new TicketPdfRenderer();
    private final QrCodeGenerator qrCodeGenerator = new QrCodeGenerator();

    private static CheckInEvent.CheckInEventBuilder baseEvent() {
        return CheckInEvent.builder()
                .type(CheckInEventType.BOARDING_PASS_GENERATED)
                .bookingReference("SBCW53")
                .passengerName("Divya Gopu")
                .contactEmail("divya@example.com")
                .flightNumber("BA178")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(LocalDateTime.of(2026, 7, 29, 10, 15))
                .travelClass("BUSINESS")
                .seatNumber("5B")
                .boardingGroup("1")
                .boardingPassNumber("BP-2026-UWFQ7D")
                .issuedAt(LocalDateTime.of(2026, 7, 28, 9, 22));
    }

    private String renderedText(CheckInEvent event) throws IOException {
        byte[] qr = qrCodeGenerator.generatePng("signed-token", 280);
        byte[] pdf = renderer.render(template.render(event, qr));
        assertThat(pdf).isNotEmpty();
        try (PDDocument document = PDDocument.load(pdf)) {
            // The stripper hard-wraps at layout line breaks - sometimes inside a
            // token ("09: 35"). Collapse whitespace and rejoin split clock values
            // so sentence-level assertions survive the wrapping.
            return new PDFTextStripper().getText(document)
                    .replaceAll("\\s+", " ")
                    .replaceAll("(\\d): (\\d)", "$1:$2");
        }
    }

    @Test
    void matchesTheFrontendPassFieldForField() throws IOException {
        String text = renderedText(baseEvent().build());

        // Identity: uppercase passenger, PNR, pass number.
        assertThat(text).contains("DIVYA GOPU");
        assertThat(text).contains("BOOKING REF (PNR)");
        assertThat(text).contains("SBCW53");
        assertThat(text).contains("BP-2026-UWFQ7D");

        // Route: codes plus the frontend's full airport names.
        assertThat(text).contains("LHR");
        assertThat(text).contains("London Heathrow");
        assertThat(text).contains("JFK");
        assertThat(text).contains("New York JFK");

        // Operational grid: readable date, departs, cabin, group, TBA gate.
        assertThat(text).contains("29 Jul 2026");
        assertThat(text).contains("10:15");
        assertThat(text).contains("Business");
        assertThat(text).contains("TBA");

        // Issue stamp.
        assertThat(text).contains("Issued 28 Jul 2026 09:22");
    }

    @Test
    void terminalsRenderInTheGridAndBothAirportBoxes() throws IOException {
        // The frontend pass shows the departure terminal in the grid and BOTH
        // terminals in the airport boxes - the emailed pass must match.
        String text = renderedText(baseEvent()
                .departureTerminal("T5")
                .arrivalTerminal("T8")
                .build());

        assertThat(text).contains("TERMINAL");
        assertThat(text).contains("T5");
        assertThat(text).contains("Terminal T5");
        assertThat(text).contains("Terminal T8");
    }

    @Test
    void missingTerminalsFallBackToTbaWithoutBoxNoise() throws IOException {
        String text = renderedText(baseEvent().build());

        // Grid shows TBA (like the frontend); the airport boxes stay clean.
        assertThat(text).contains("TERMINAL");
        assertThat(text).doesNotContain("Terminal null");
    }

    @Test
    void boardingReadsEarlierThanDepartureWhenServerEchoesTheDepartureClock() throws IOException {
        // checkin-service stamps boardingTime with the departure clock - the
        // display must derive departure - 40, and the advisory 30 before that.
        String text = renderedText(baseEvent()
                .boardingTime(LocalDateTime.of(2025, 7, 29, 10, 15))
                .build());

        assertThat(text).contains("09:35");
        assertThat(text).contains("arrive at the boarding gate by 09:05, 30 minutes before boarding begins at 09:35");
    }

    @Test
    void aGenuinelyEarlierServerBoardingTimeWins() throws IOException {
        String text = renderedText(baseEvent()
                .boardingTime(LocalDateTime.of(2026, 7, 29, 9, 30))
                .build());

        assertThat(text).contains("09:30");
        assertThat(text).contains("arrive at the boarding gate by 09:00");
    }
}
