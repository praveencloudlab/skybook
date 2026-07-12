package com.skybook.praveen.authservice.producer;

import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailEventProducer {

    private final KafkaTemplate<String, EmailEvent> kafkaTemplate;

    public void sendEmailEvent(EmailEvent event) {

        // Send stays async (no .get() in a request path) but is no longer
        // fire-and-forget: a broker-side failure now logs at ERROR into the
        // centralized pipeline instead of vanishing (RESILIENCE_MODULE.md §10).
        kafkaTemplate.send(KafkaTopics.EMAIL_EVENTS, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish EmailEvent to {} for {}",
                                KafkaTopics.EMAIL_EVENTS, event.getTo(), ex);
                    }
                });
    }

}
