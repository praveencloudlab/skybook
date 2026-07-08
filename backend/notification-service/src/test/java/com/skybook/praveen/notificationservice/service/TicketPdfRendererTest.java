package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TicketPdfRendererTest {

    private final TicketPdfTemplate template = new TicketPdfTemplate();
    private final TicketPdfRenderer renderer = new TicketPdfRenderer();
    private final QrCodeGenerator qrCodeGenerator = new QrCodeGenerator();

    @Test
    void rendersAWellFormedPdfWithQrCode() {

        BookingEvent event = BookingEvent.builder()
                .type(BookingEventType.CONFIRMED)
                .bookingReference("SB8U33")
                .contactEmail("praveen.somireddy@gmail.com")
                .contactName("Praveen Somireddy")
                .bookingDate("2026-07-08 10:15")
                .flightNumber("BA178")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime("2026-07-08 10:15")
                .arrivalTime("2026-07-08 18:25")
                .totalFare(new BigDecimal("450.00"))
                .currency("GBP")
                .paymentStatus("PAID")
                .passengers(List.of(BookingEventPassenger.builder()
                        .name("Praveen Somireddy")
                        .seatNumber("12B")
                        .travelClass("ECONOMY")
                        .fareType("FLEXI")
                        .fare(new BigDecimal("450.00"))
                        .checkInStatus("NOT_OPEN")
                        .build()))
                .build();

        byte[] qr = qrCodeGenerator.generatePng("SKYBOOK|SB8U33|FLIGHT 1|Praveen Somireddy", 280);
        String xhtml = template.render(event, qr);
        byte[] pdf = renderer.render(xhtml);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
