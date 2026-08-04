package com.skybook.praveen.notificationservice.consumer;

import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.common.event.CheckInEventType;
import com.skybook.praveen.notificationservice.service.BoardingPassPdfTemplate;
import com.skybook.praveen.notificationservice.service.CheckInEmailTemplate;
import com.skybook.praveen.notificationservice.service.EmailService;
import com.skybook.praveen.notificationservice.service.QrCodeGenerator;
import com.skybook.praveen.notificationservice.service.TicketPdfRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * checkin-service publishes two events for one check-in and only
 * BOARDING_PASS_GENERATED carries the pass details, so acting on the other
 * would mail the passenger a second, mostly-blank pass. These pin that
 * filter and the shape of the message that does go out.
 */
@ExtendWith(MockitoExtension.class)
class CheckInEventConsumerTest {

    @Mock
    private EmailService emailService;
    @Mock
    private CheckInEmailTemplate checkInEmailTemplate;
    @Mock
    private BoardingPassPdfTemplate boardingPassPdfTemplate;
    @Mock
    private QrCodeGenerator qrCodeGenerator;
    @Mock
    private TicketPdfRenderer ticketPdfRenderer;

    private CheckInEventConsumer consumer;

    private static final byte[] QR = {1, 2, 3};
    private static final byte[] PDF = {4, 5, 6};

    /** Anchored to now so the fixture never expires. */
    private static final LocalDateTime DEPARTURE =
            LocalDateTime.now().plusDays(30).withHour(9).withMinute(15).withSecond(0).withNano(0);

    @BeforeEach
    void setUp() {
        consumer = new CheckInEventConsumer(emailService, checkInEmailTemplate, boardingPassPdfTemplate,
                qrCodeGenerator, ticketPdfRenderer);
    }

    private static CheckInEvent.CheckInEventBuilder boardingPassGenerated() {
        return CheckInEvent.builder()
                .type(CheckInEventType.BOARDING_PASS_GENERATED)
                .bookingReference("SBCW53")
                .passengerName("Divya Gopu")
                .contactEmail("divya@example.com")
                .flightNumber("BA178")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(DEPARTURE)
                .seatNumber("5B")
                .boardingGroup("1")
                .boardingPassNumber("BP-2026-UWFQ7D")
                .token("signed.boarding.token")
                .issuedAt(DEPARTURE.minusDays(1));
    }

    @Nested
    @DisplayName("Events that must not produce an email")
    class SkippedEvents {

        @Test
        void theCheckedInEventIsIgnoredSoThePassengerGetsOneEmailNotTwo() {
            consumer.consume(boardingPassGenerated()
                    .type(CheckInEventType.PASSENGER_CHECKED_IN)
                    .build());

            verifyNoInteractions(emailService, checkInEmailTemplate, boardingPassPdfTemplate,
                    qrCodeGenerator, ticketPdfRenderer);
        }

        @Test
        void gateAndNoShowEventsAreIgnoredToo() {
            consumer.consume(boardingPassGenerated().type(CheckInEventType.PASSENGER_BOARDED).build());
            consumer.consume(boardingPassGenerated().type(CheckInEventType.PASSENGER_NO_SHOW).build());
            consumer.consume(boardingPassGenerated().type(CheckInEventType.PASSENGER_CHECKIN_CANCELLED).build());

            verifyNoInteractions(emailService, checkInEmailTemplate, boardingPassPdfTemplate,
                    qrCodeGenerator, ticketPdfRenderer);
        }

        @Test
        void aPassWithNoContactEmailIsDropped() {
            consumer.consume(boardingPassGenerated().contactEmail(null).build());

            verifyNoInteractions(emailService, checkInEmailTemplate, boardingPassPdfTemplate,
                    qrCodeGenerator, ticketPdfRenderer);
        }

        @Test
        void aPassWithABlankContactEmailIsDropped() {
            consumer.consume(boardingPassGenerated().contactEmail(" ").build());

            verifyNoInteractions(emailService, checkInEmailTemplate, boardingPassPdfTemplate,
                    qrCodeGenerator, ticketPdfRenderer);
        }
    }

    @Nested
    @DisplayName("The boarding pass email")
    class BoardingPassEmail {

        @Test
        void carriesTheInlineQrAndThePrintablePassNamedAfterItsPassNumber() {
            CheckInEvent event = boardingPassGenerated().build();
            when(qrCodeGenerator.generatePng(eq("signed.boarding.token"), eq(280))).thenReturn(QR);
            when(checkInEmailTemplate.render(event)).thenReturn("<html>pass</html>");
            when(boardingPassPdfTemplate.render(event, QR)).thenReturn("<xhtml/>");
            when(ticketPdfRenderer.render("<xhtml/>")).thenReturn(PDF);

            consumer.consume(event);

            verify(emailService).sendHtmlEmail(
                    "divya@example.com",
                    "Your boarding pass - SBCW53",
                    "<html>pass</html>",
                    CheckInEmailTemplate.QR_CID, QR,
                    "SkyBook-BoardingPass-BP-2026-UWFQ7D.pdf", PDF);
        }

        @Test
        void theQrEncodesTheSignedTokenSoTheGateCanValidateIt() {
            consumer.consume(boardingPassGenerated().build());

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(qrCodeGenerator).generatePng(payload.capture(), eq(280));

            assertThat(payload.getValue()).isEqualTo("signed.boarding.token");
        }

        @Test
        void anUnsignedPassFallsBackToAHumanReadablePayload() {
            consumer.consume(boardingPassGenerated().token(null).build());

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(qrCodeGenerator).generatePng(payload.capture(), eq(280));

            assertThat(payload.getValue()).isEqualTo("SKYBOOK-BOARDING|SBCW53|BP-2026-UWFQ7D");
        }
    }
}
