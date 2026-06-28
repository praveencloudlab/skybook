package com.skybook.praveen.notificationservice.consumer;

import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.notificationservice.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailEventConsumer {

    private final EmailService emailService;

    @KafkaListener(topics = "${skybook.kafka.topics.email-events}")
    public void consume(EmailEvent event) {

        log.info("Received Email Event: {}", event);

        emailService.sendEmail(event);
    }
}