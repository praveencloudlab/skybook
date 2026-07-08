package com.skybook.praveen.notificationservice.consumer;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventType;
import com.skybook.praveen.notificationservice.service.BookingEmailTemplate;
import com.skybook.praveen.notificationservice.service.EmailService;
import com.skybook.praveen.notificationservice.service.QrCodeGenerator;
import com.skybook.praveen.notificationservice.service.TicketPdfRenderer;
import com.skybook.praveen.notificationservice.service.TicketPdfTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes BookingEvent from skybook.booking.events (published by
 * booking-service's BookingEventProducer) and turns it into an email.
 *
 * Events carrying structured booking details get the HTML template
 * (BookingEmailTemplate); lean/older events fall back to the plain-text
 * subject + message the producer composed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final EmailService emailService;
    private final BookingEmailTemplate bookingEmailTemplate;
    private final QrCodeGenerator qrCodeGenerator;
    private final TicketPdfTemplate ticketPdfTemplate;
    private final TicketPdfRenderer ticketPdfRenderer;

    @KafkaListener(
            topics = "${skybook.kafka.topics.booking-events}",
            containerFactory = "bookingEventContainerFactory")
    public void consume(BookingEvent event) {

        log.info("Received Booking Event: {} for {}", event.getType(), event.getBookingReference());

        if (event.getContactEmail() == null || event.getContactEmail().isBlank()) {
            log.warn("Booking event {} for {} has no contact email - skipping",
                    event.getType(), event.getBookingReference());
            return;
        }

        if (event.getPassengers() != null && !event.getPassengers().isEmpty()) {

            // QR only where it's useful - not on cancellation/expiry notices.
            boolean includeQr = event.getType() == BookingEventType.CREATED
                    || event.getType() == BookingEventType.CONFIRMED
                    || event.getType() == BookingEventType.COMPLETED;

            // Downloadable ticket PDF only once the booking is actually
            // confirmed (paid) - a PDF for a pending booking isn't a ticket.
            boolean includeTicket = event.getType() == BookingEventType.CONFIRMED;

            String html = bookingEmailTemplate.render(event, includeQr);
            byte[] qr = includeQr ? qrCodeGenerator.generatePng(qrPayload(event), 280) : null;

            if (includeTicket) {
                byte[] ticketPdf = ticketPdfRenderer.render(ticketPdfTemplate.render(event, qr));
                emailService.sendHtmlEmail(event.getContactEmail(), event.getSubject(), html,
                        BookingEmailTemplate.QR_CID, qr,
                        "SkyBook-Ticket-" + event.getBookingReference() + ".pdf", ticketPdf);
            } else if (includeQr) {
                emailService.sendHtmlEmail(event.getContactEmail(), event.getSubject(), html,
                        BookingEmailTemplate.QR_CID, qr);
            } else {
                emailService.sendHtmlEmail(event.getContactEmail(), event.getSubject(), html);
            }
        } else {
            emailService.sendEmail(event.getContactEmail(), event.getSubject(), event.getMessage());
        }
    }

    /**
     * Compact, scannable payload. PNR is the lookup key; the rest is
     * human-readable context. Swap for a check-in URL once a public
     * front end exists.
     */
    private String qrPayload(BookingEvent event) {
        return "SKYBOOK|" + event.getBookingReference()
                + "|FLIGHT " + (event.getFlightId() != null ? event.getFlightId() : "?")
                + "|" + (event.getContactName() != null ? event.getContactName() : "");
    }
}
