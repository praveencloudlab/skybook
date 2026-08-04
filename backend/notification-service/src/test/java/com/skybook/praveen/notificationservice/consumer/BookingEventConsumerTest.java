package com.skybook.praveen.notificationservice.consumer;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventType;
import com.skybook.praveen.notificationservice.service.BookingEmailTemplate;
import com.skybook.praveen.notificationservice.service.EmailService;
import com.skybook.praveen.notificationservice.service.QrCodeGenerator;
import com.skybook.praveen.notificationservice.service.TicketPdfRenderer;
import com.skybook.praveen.notificationservice.service.TicketPdfTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * What the consumer decides is what the traveller receives: a QR only where
 * it is useful, and a ticket PDF only once the booking is paid for - a PDF
 * attached to a pending booking would be a ticket for a seat nobody bought.
 * A missing recipient must be dropped quietly rather than thrown, or the
 * listener would replay the same event forever.
 */
@ExtendWith(MockitoExtension.class)
class BookingEventConsumerTest {

    @Mock
    private EmailService emailService;
    @Mock
    private BookingEmailTemplate bookingEmailTemplate;
    @Mock
    private QrCodeGenerator qrCodeGenerator;
    @Mock
    private TicketPdfTemplate ticketPdfTemplate;
    @Mock
    private TicketPdfRenderer ticketPdfRenderer;

    private BookingEventConsumer consumer;

    private static final byte[] QR = {1, 2, 3};
    private static final byte[] PDF = {4, 5, 6};

    @BeforeEach
    void setUp() {
        consumer = new BookingEventConsumer(emailService, bookingEmailTemplate, qrCodeGenerator,
                ticketPdfTemplate, ticketPdfRenderer);
    }

    private static BookingEvent.BookingEventBuilder event(BookingEventType type) {
        return BookingEvent.builder()
                .type(type)
                .bookingReference("SB8U33")
                .bookingId(42L)
                .flightId(7L)
                .contactEmail("praveen.somireddy@gmail.com")
                .contactName("Praveen Somireddy")
                .subject("Your SkyBook booking is " + type.name().toLowerCase())
                .message("Plain text fallback.")
                .passengers(List.of(BookingEventPassenger.builder()
                        .name("Praveen Somireddy")
                        .seatNumber("12B")
                        .travelClass("ECONOMY")
                        .fareType("FLEXI")
                        .fare(new BigDecimal("450.00"))
                        .build()));
    }

    @Nested
    @DisplayName("Events that must not produce an email")
    class SkippedEvents {

        @Test
        void anEventWithoutAContactEmailIsDropped() {
            consumer.consume(event(BookingEventType.CONFIRMED).contactEmail(null).build());

            verifyNoInteractions(emailService, bookingEmailTemplate, qrCodeGenerator,
                    ticketPdfTemplate, ticketPdfRenderer);
        }

        @Test
        void anEventWithABlankContactEmailIsDropped() {
            consumer.consume(event(BookingEventType.CONFIRMED).contactEmail("   ").build());

            verifyNoInteractions(emailService, bookingEmailTemplate, qrCodeGenerator,
                    ticketPdfTemplate, ticketPdfRenderer);
        }
    }

    @Nested
    @DisplayName("Lean events")
    class LeanEvents {

        @Test
        void anEventWithoutPassengersFallsBackToTheProducersPlainText() {
            consumer.consume(event(BookingEventType.CREATED).passengers(null).build());

            verify(emailService).sendEmail("praveen.somireddy@gmail.com",
                    "Your SkyBook booking is created", "Plain text fallback.");
            verifyNoInteractions(bookingEmailTemplate, qrCodeGenerator, ticketPdfTemplate, ticketPdfRenderer);
        }

        @Test
        void anEventWithAnEmptyPassengerListAlsoFallsBackToPlainText() {
            consumer.consume(event(BookingEventType.FARE_ALERT).passengers(List.of()).build());

            verify(emailService).sendEmail(anyString(), anyString(), anyString());
            verifyNoInteractions(bookingEmailTemplate);
        }
    }

    @Nested
    @DisplayName("Rich events")
    class RichEvents {

        @Test
        void aConfirmedBookingCarriesTheInlineQrAndTheTicketPdf() {
            BookingEvent booking = event(BookingEventType.CONFIRMED).build();
            when(bookingEmailTemplate.render(booking, true)).thenReturn("<html>confirmed</html>");
            when(qrCodeGenerator.generatePng(anyString(), eq(280))).thenReturn(QR);
            when(ticketPdfTemplate.render(booking, QR)).thenReturn("<xhtml/>");
            when(ticketPdfRenderer.render("<xhtml/>")).thenReturn(PDF);

            consumer.consume(booking);

            verify(emailService).sendHtmlEmail("praveen.somireddy@gmail.com",
                    "Your SkyBook booking is confirmed", "<html>confirmed</html>",
                    BookingEmailTemplate.QR_CID, QR,
                    "SkyBook-Ticket-SB8U33.pdf", PDF);
            verifyNoMoreInteractions(emailService);
        }

        @Test
        void anUnpaidBookingGetsTheQrButNoTicketYet() {
            BookingEvent booking = event(BookingEventType.CREATED).build();
            when(bookingEmailTemplate.render(booking, true)).thenReturn("<html>created</html>");
            when(qrCodeGenerator.generatePng(anyString(), eq(280))).thenReturn(QR);

            consumer.consume(booking);

            verify(emailService).sendHtmlEmail("praveen.somireddy@gmail.com",
                    "Your SkyBook booking is created", "<html>created</html>",
                    BookingEmailTemplate.QR_CID, QR);
            verifyNoMoreInteractions(emailService);
            verifyNoInteractions(ticketPdfTemplate, ticketPdfRenderer);
        }

        @Test
        void aCompletedBookingGetsTheQrWithoutReissuingTheTicket() {
            BookingEvent booking = event(BookingEventType.COMPLETED).build();
            when(bookingEmailTemplate.render(booking, true)).thenReturn("<html>completed</html>");
            when(qrCodeGenerator.generatePng(anyString(), eq(280))).thenReturn(QR);

            consumer.consume(booking);

            verify(emailService).sendHtmlEmail(anyString(), anyString(), eq("<html>completed</html>"),
                    eq(BookingEmailTemplate.QR_CID), eq(QR));
            verifyNoInteractions(ticketPdfTemplate, ticketPdfRenderer);
        }

        @Test
        void aCancellationNoticeCarriesNeitherQrNorTicket() {
            BookingEvent booking = event(BookingEventType.CANCELLED).build();
            when(bookingEmailTemplate.render(booking, false)).thenReturn("<html>cancelled</html>");

            consumer.consume(booking);

            verify(emailService).sendHtmlEmail("praveen.somireddy@gmail.com",
                    "Your SkyBook booking is cancelled", "<html>cancelled</html>");
            verifyNoMoreInteractions(emailService);
            verifyNoInteractions(qrCodeGenerator, ticketPdfTemplate, ticketPdfRenderer);
        }

        @Test
        void anExpiredBookingIsAlsoDeniedTheQr() {
            BookingEvent booking = event(BookingEventType.EXPIRED).build();
            when(bookingEmailTemplate.render(booking, false)).thenReturn("<html>expired</html>");

            consumer.consume(booking);

            verify(emailService).sendHtmlEmail(anyString(), anyString(), eq("<html>expired</html>"));
            verifyNoInteractions(qrCodeGenerator);
        }
    }

    @Nested
    @DisplayName("QR payload")
    class QrPayload {

        @Test
        void carriesThePnrFlightAndContactNameAtTheSizeTheEmailReserves() {
            consumer.consume(event(BookingEventType.CREATED).build());

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(qrCodeGenerator).generatePng(payload.capture(), eq(280));

            assertThat(payload.getValue()).isEqualTo("SKYBOOK|SB8U33|FLIGHT 7|Praveen Somireddy");
        }

        @Test
        void degradesToPlaceholdersWhenTheFlightAndContactAreMissing() {
            consumer.consume(event(BookingEventType.CREATED)
                    .flightId(null)
                    .contactName(null)
                    .build());

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(qrCodeGenerator).generatePng(payload.capture(), eq(280));

            assertThat(payload.getValue()).isEqualTo("SKYBOOK|SB8U33|FLIGHT ?|");
        }
    }
}
