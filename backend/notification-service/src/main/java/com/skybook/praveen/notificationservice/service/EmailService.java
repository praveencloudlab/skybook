package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.EmailEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {

    public void sendEmail(EmailEvent event) {

        log.info("=======================================");
        log.info("SKYBOOK EMAIL");
        log.info("To      : {}", event.getTo());
        log.info("Subject : {}", event.getSubject());
        log.info("Type    : {}", event.getType());
        log.info("Body    : {}", event.getBody());
        log.info("=======================================");
    }
}