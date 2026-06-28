package com.skybook.praveen.authservice.producer;

import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.EmailEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailEventProducer {

    private final KafkaTemplate<String, EmailEvent> kafkaTemplate;

    public void sendEmailEvent(EmailEvent event) {

        kafkaTemplate.send(KafkaTopics.EMAIL_EVENTS, event);

    }

}