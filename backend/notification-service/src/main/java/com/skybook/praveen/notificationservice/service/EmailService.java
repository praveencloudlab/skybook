package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.EmailEvent;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendEmail(EmailEvent emailEvent) {
        sendEmail(emailEvent.getTo(), emailEvent.getSubject(), emailEvent.getBody());
    }

    /** Plain variant for events that carry their own composed subject/body (BookingEvent). */
    public void sendEmail(String to, String subject, String body) {

        SimpleMailMessage message = new SimpleMailMessage();

        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        mailSender.send(message);

        System.out.println("✅ Email sent successfully to " + to);
    }

    /** HTML variant - used for the rich booking notification template. */
    public void sendHtmlEmail(String to, String subject, String html) {
        sendHtmlEmail(to, subject, html, null, null);
    }

    /**
     * HTML with an optional inline image (e.g. the booking QR code),
     * referenced from the HTML as {@code <img src="cid:CONTENT_ID">}.
     * Inline CID attachments render in Gmail/Outlook where base64 data URIs
     * are stripped.
     */
    public void sendHtmlEmail(String to, String subject, String html, String inlineImageCid, byte[] inlineImagePng) {

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, inlineImagePng != null, "UTF-8");

            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            // Must come AFTER setText, or the inline part is dropped.
            if (inlineImagePng != null) {
                helper.addInline(inlineImageCid,
                        new org.springframework.core.io.ByteArrayResource(inlineImagePng), "image/png");
            }

            mailSender.send(mimeMessage);

            System.out.println("✅ HTML email sent successfully to " + to);

        } catch (jakarta.mail.MessagingException e) {
            throw new MailPreparationException("Could not build HTML email to " + to, e);
        }
    }
}