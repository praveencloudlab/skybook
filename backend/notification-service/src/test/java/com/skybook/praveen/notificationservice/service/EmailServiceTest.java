package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.common.event.EmailType;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The MIME structure is the whole point of this class: the QR the booking
 * email references as {@code cid:skybook-qr} only renders if it is attached
 * inline under exactly that content id, and the ticket only reaches the
 * traveller if the attachment keeps its filename. These assert the message
 * handed to the sender rather than that send() was called.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender);
    }

    private MimeMessage stubMimeMessage() {
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        return message;
    }

    /** Every leaf part of the message, whatever nesting the helper chose. */
    private static List<MimeBodyPart> leafParts(Part part) throws Exception {
        List<MimeBodyPart> leaves = new ArrayList<>();
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                leaves.addAll(leafParts(child));
            }
        } else if (part instanceof MimeBodyPart leaf) {
            leaves.add(leaf);
        }
        return leaves;
    }

    @Nested
    @DisplayName("Plain text")
    class PlainText {

        @Test
        void anEmailEventIsSentToItsOwnRecipientSubjectAndBody() {
            emailService.sendEmail(EmailEvent.builder()
                    .to("praveen.somireddy@gmail.com")
                    .subject("Welcome to SkyBook")
                    .body("Your account is ready.")
                    .type(EmailType.REGISTRATION_SUCCESS)
                    .build());

            ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender).send(captor.capture());

            SimpleMailMessage sent = captor.getValue();
            assertThat(sent.getTo()).containsExactly("praveen.somireddy@gmail.com");
            assertThat(sent.getSubject()).isEqualTo("Welcome to SkyBook");
            assertThat(sent.getText()).isEqualTo("Your account is ready.");
        }

        @Test
        void aComposedSubjectAndBodyAreSentVerbatim() {
            emailService.sendEmail("divya@example.com", "Booking SB8U33 cancelled", "Your booking was cancelled.");

            ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender).send(captor.capture());

            assertThat(captor.getValue().getTo()).containsExactly("divya@example.com");
            assertThat(captor.getValue().getSubject()).isEqualTo("Booking SB8U33 cancelled");
            assertThat(captor.getValue().getText()).isEqualTo("Your booking was cancelled.");
        }
    }

    @Nested
    @DisplayName("HTML")
    class Html {

        @Test
        void anHtmlOnlyEmailIsASingleHtmlPartRatherThanAMultipart() throws Exception {
            MimeMessage message = stubMimeMessage();

            emailService.sendHtmlEmail("divya@example.com", "Booking confirmed", "<html>hi</html>");

            verify(mailSender).send(message);
            assertThat(message.getAllRecipients()).hasSize(1);
            assertThat(message.getAllRecipients()[0]).hasToString("divya@example.com");
            assertThat(message.getSubject()).isEqualTo("Booking confirmed");
            assertThat(message.getContent()).isEqualTo("<html>hi</html>");
            assertThat(message.getDataHandler().getContentType()).startsWith("text/html");
        }

        @Test
        void anInlineImageIsAttachedUnderTheContentIdTheHtmlReferences() throws Exception {
            MimeMessage message = stubMimeMessage();
            byte[] qr = {1, 2, 3, 4};

            emailService.sendHtmlEmail("divya@example.com", "Booking confirmed",
                    "<html><img src=\"cid:skybook-qr\"></html>", "skybook-qr", qr);

            verify(mailSender).send(message);
            List<MimeBodyPart> parts = leafParts(message);
            assertThat(parts).anySatisfy(part -> {
                assertThat(part.getContentID()).isEqualTo("<skybook-qr>");
                assertThat(part.getDataHandler().getContentType()).isEqualTo("image/png");
            });
            assertThat(parts).anySatisfy(part ->
                    assertThat(part.getContent()).isEqualTo("<html><img src=\"cid:skybook-qr\"></html>"));
        }

        @Test
        void theTicketPdfKeepsItsFilenameAlongsideTheInlineQr() throws Exception {
            MimeMessage message = stubMimeMessage();

            emailService.sendHtmlEmail("divya@example.com", "Booking confirmed", "<html>hi</html>",
                    "skybook-qr", new byte[]{1, 2, 3, 4},
                    "SkyBook-Ticket-SB8U33.pdf", new byte[]{5, 6, 7, 8});

            verify(mailSender).send(message);
            List<MimeBodyPart> parts = leafParts(message);
            assertThat(parts).anySatisfy(part -> {
                assertThat(part.getFileName()).isEqualTo("SkyBook-Ticket-SB8U33.pdf");
                assertThat(part.getDataHandler().getContentType()).isEqualTo("application/pdf");
            });
            assertThat(parts).anySatisfy(part ->
                    assertThat(part.getContentID()).isEqualTo("<skybook-qr>"));
        }

        @Test
        void anAttachmentWithoutAnInlineImageStillProducesAMultipart() throws Exception {
            MimeMessage message = stubMimeMessage();

            emailService.sendHtmlEmail("divya@example.com", "Your boarding pass", "<html>hi</html>",
                    null, null, "SkyBook-BoardingPass-BP1.pdf", new byte[]{9});

            verify(mailSender).send(message);
            assertThat(message.getContent()).isInstanceOf(Multipart.class);
            assertThat(leafParts(message)).anySatisfy(part ->
                    assertThat(part.getFileName()).isEqualTo("SkyBook-BoardingPass-BP1.pdf"));
        }

        @Test
        void anUnusableRecipientFailsLoudlyBeforeAnythingIsSent() {
            stubMimeMessage();

            // A single recipient field can hold exactly one address; two is a
            // caller bug that must not reach the mail server half-formed.
            assertThatThrownBy(() -> emailService.sendHtmlEmail(
                    "divya@example.com, praveen@example.com", "Booking confirmed", "<html>hi</html>"))
                    .isInstanceOf(MailPreparationException.class)
                    .hasMessageContaining("Could not build HTML email to");

            verify(mailSender, never()).send(any(MimeMessage.class));
        }
    }
}
