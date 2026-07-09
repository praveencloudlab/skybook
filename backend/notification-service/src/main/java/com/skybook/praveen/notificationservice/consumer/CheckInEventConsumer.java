package com.skybook.praveen.notificationservice.consumer;

import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.common.event.CheckInEventType;
import com.skybook.praveen.notificationservice.service.BoardingPassPdfTemplate;
import com.skybook.praveen.notificationservice.service.CheckInEmailTemplate;
import com.skybook.praveen.notificationservice.service.EmailService;
import com.skybook.praveen.notificationservice.service.QrCodeGenerator;
import com.skybook.praveen.notificationservice.service.TicketPdfRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes CheckInEvent from skybook.checkin.events (published by
 * checkin-service's CheckInEventProducer) and emails the boarding pass.
 *
 * Only BOARDING_PASS_GENERATED triggers an email - checkin-service's
 * CheckInFacade.checkIn() publishes both PASSENGER_CHECKED_IN and
 * BOARDING_PASS_GENERATED for the same check-in, and BOARDING_PASS_GENERATED
 * is the one carrying the pass number/token/gate/boarding-time details, so
 * acting on both would send a duplicate email with a mostly-blank first copy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckInEventConsumer {

    private final EmailService emailService;
    private final CheckInEmailTemplate checkInEmailTemplate;
    private final BoardingPassPdfTemplate boardingPassPdfTemplate;
    private final QrCodeGenerator qrCodeGenerator;
    private final TicketPdfRenderer ticketPdfRenderer;

    @KafkaListener(
            topics = "${skybook.kafka.topics.checkin-events}",
            containerFactory = "checkInEventContainerFactory")
    public void consume(CheckInEvent event) {

        log.info("Received CheckIn Event: {} for booking {}", event.getType(), event.getBookingReference());

        if (event.getType() != CheckInEventType.BOARDING_PASS_GENERATED) {
            return;
        }

        if (event.getContactEmail() == null || event.getContactEmail().isBlank()) {
            log.warn("CheckIn event for booking {} has no contact email - skipping",
                    event.getBookingReference());
            return;
        }

        byte[] qr = qrCodeGenerator.generatePng(qrPayload(event), 280);

        String html = checkInEmailTemplate.render(event);
        byte[] boardingPassPdf = ticketPdfRenderer.render(boardingPassPdfTemplate.render(event, qr));

        emailService.sendHtmlEmail(
                event.getContactEmail(),
                "Your boarding pass - " + event.getBookingReference(),
                html,
                CheckInEmailTemplate.QR_CID, qr,
                "SkyBook-BoardingPass-" + event.getBoardingPassNumber() + ".pdf", boardingPassPdf);
    }

    /**
     * Same payload shape the checkin-service token encodes into: scanning
     * this QR at the gate is meant to validate against
     * BoardingPassTokenSigner's token, not this display payload, but until a
     * gate-scanner app exists this stays human-readable like the booking QR.
     */
    private String qrPayload(CheckInEvent event) {
        return event.getToken() != null ? event.getToken()
                : "SKYBOOK-BOARDING|" + event.getBookingReference() + "|" + event.getBoardingPassNumber();
    }
}
