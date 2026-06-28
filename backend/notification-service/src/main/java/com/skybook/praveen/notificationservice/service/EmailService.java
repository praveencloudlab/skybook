package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.EmailEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendEmail(EmailEvent emailEvent) {

        SimpleMailMessage message = new SimpleMailMessage();

        message.setTo(emailEvent.getTo());
        message.setSubject(emailEvent.getSubject());
        message.setText(emailEvent.getBody());

        mailSender.send(message);

        System.out.println("✅ Email sent successfully to " + emailEvent.getTo());
    }
}