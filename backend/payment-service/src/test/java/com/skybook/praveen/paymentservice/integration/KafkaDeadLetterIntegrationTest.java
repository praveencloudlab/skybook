package com.skybook.praveen.paymentservice.integration;

import com.skybook.praveen.common.event.BookingEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the configured error-handling pipeline end to end against a real
 * broker (RESILIENCE_MODULE.md §9/§15): a failing record is retried exactly
 * 3 times (bounded backoff - the design-review catch) and then lands on the
 * .DLT with exception metadata; a poison pill (undeserializable bytes) goes
 * to the DLT without wedging the consumer, which keeps consuming.
 *
 * Uses a dedicated test topic + a test listener built on the SAME
 * bookingEventContainerFactory the production listener uses, so what's
 * exercised is the real factory + error handler + recoverer wiring.
 */
@Import(KafkaDeadLetterIntegrationTest.FailingListenerConfig.class)
class KafkaDeadLetterIntegrationTest extends AbstractPaymentIntegrationTest {

    static final String TEST_TOPIC = "skybook.dlt-test.events";
    // spring-kafka's actual default suffix in this version is "-dlt" - NOT
    // the ".DLT" the design doc originally assumed (found empirically: the
    // recoverer's UNKNOWN_TOPIC warning named "...events-dlt" while this
    // test sat watching "...events.DLT" forever).
    static final String TEST_DLT = TEST_TOPIC + "-dlt";

    @TestConfiguration
    static class FailingListenerConfig {

        @Component
        static class AlwaysFailingListener {

            final AtomicInteger deliveries = new AtomicInteger();

            @KafkaListener(topics = TEST_TOPIC, containerFactory = "bookingEventContainerFactory",
                    groupId = "dlt-test")
            public void consume(BookingEvent event) {
                deliveries.incrementAndGet();
                throw new IllegalStateException("deliberate failure for DLT test");
            }
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private FailingListenerConfig.AlwaysFailingListener failingListener;

    private KafkaProducer<String, String> rawProducer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    private KafkaConsumer<String, String> dltConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-assertions-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TEST_DLT));
        return consumer;
    }

    /** Both tests share the DLT topic - poll until a record MATCHING this test's payload appears. */
    private ConsumerRecord<String, String> awaitDltRecord(String expectedValueFragment) {
        try (KafkaConsumer<String, String> consumer = dltConsumer()) {
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : polled) {
                    if (record.value() != null && record.value().contains(expectedValueFragment)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no record containing '" + expectedValueFragment
                + "' arrived on " + TEST_DLT + " within 30s");
    }

    @Test
    void failingRecordIsRetriedThreeTimesThenDeadLettered() {
        int deliveriesBefore = failingListener.deliveries.get();

        try (KafkaProducer<String, String> producer = rawProducer()) {
            producer.send(new ProducerRecord<>(TEST_TOPIC,
                    "{\"type\":\"CREATED\",\"bookingId\":999,\"bookingReference\":\"DLT-TEST\"}"));
            producer.flush();
        }

        ConsumerRecord<String, String> dead = awaitDltRecord("DLT-TEST");

        assertThat(dead.value()).contains("DLT-TEST");
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-message").value()))
                .contains("deliberate failure");
        assertThat(new String(dead.headers().lastHeader("kafka_dlt-original-topic").value()))
                .isEqualTo(TEST_TOPIC);

        // Bounded retries (ExponentialBackOffWithMaxRetries(2)): exactly 3
        // deliveries - not spring-kafka's default 10, not unbounded.
        assertThat(failingListener.deliveries.get() - deliveriesBefore).isEqualTo(3);
    }

    @Test
    void poisonPillGoesToDltWithoutWedgingTheConsumer() {
        try (KafkaProducer<String, String> producer = rawProducer()) {
            producer.send(new ProducerRecord<>(TEST_TOPIC, "this is not json {{{"));
            producer.flush();
        }

        ConsumerRecord<String, String> dead = awaitDltRecord("this is not json");

        assertThat(dead.value()).contains("this is not json");
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-fqcn").value()))
                .contains("DeserializationException");
    }
}
