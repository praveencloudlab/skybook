package com.skybook.praveen.paymentservice;

import com.skybook.praveen.paymentservice.consumer.BookingEventConsumer;
import com.skybook.praveen.paymentservice.facade.PaymentFacade;
import com.skybook.praveen.paymentservice.integration.AbstractPaymentIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load smoke test from day one this time (the gap Sprint 4 closed
 * late): full context against real PostgreSQL + Kafka - JPA, both Kafka
 * factories, the consumer subscription, gateway, security chain, and the
 * invoice sequence @PostConstruct.
 */
class PaymentServiceApplicationTests extends AbstractPaymentIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void keyBeansAreWired() {
        assertThat(context.getBean(PaymentFacade.class)).isNotNull();
        assertThat(context.getBean(BookingEventConsumer.class)).isNotNull();
    }
}
