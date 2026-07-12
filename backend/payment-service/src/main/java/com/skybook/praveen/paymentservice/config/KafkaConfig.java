package com.skybook.praveen.paymentservice.config;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.PaymentEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Producer (PaymentEvent out) and consumer (BookingEvent in) in one config.
 * The consumer container factory is explicitly named and referenced by the
 * listener - the lesson from notification-service's two-factory setup
 * applied from day one.
 *
 * Resilience (RESILIENCE_MODULE.md §9): the JsonDeserializer is wrapped in
 * ErrorHandlingDeserializer (a malformed record previously wedged this
 * consumer in an infinite retry loop), and the container factory carries a
 * DefaultErrorHandler with explicitly bounded exponential backoff - attempt
 * 1 -> 1s -> attempt 2 -> 2s -> attempt 3 -> skybook.booking.events.DLT -
 * replacing spring-kafka's default of 10 zero-interval retries followed by
 * silently discarding the message.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ---------------------------------------------------------------
    // Producer: PaymentEvent -> skybook.payment.events
    // ---------------------------------------------------------------

    @Bean
    public ProducerFactory<String, PaymentEvent> producerFactory() {

        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ---------------------------------------------------------------
    // Consumer: BookingEvent <- skybook.booking.events
    // ---------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, BookingEvent> bookingEventConsumerFactory() {

        JsonDeserializer<BookingEvent> json = new JsonDeserializer<>(BookingEvent.class);
        json.addTrustedPackages("com.skybook.praveen.common.event");

        // Poison-pill guard (§9): a record that can't deserialize fails into
        // the error handler (-> DLT) instead of looping forever before the
        // listener is ever reached.
        ErrorHandlingDeserializer<BookingEvent> value = new ErrorHandlingDeserializer<>(json);

        Map<String, Object> config = new HashMap<>();

        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-service");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new DefaultKafkaConsumerFactory<>(
                config,
                new StringDeserializer(),
                value
        );
    }

    /**
     * Dead-letter publisher used by the error handler. Two templates because
     * the failed record's value differs by failure type: a processing
     * failure carries the deserialized event (JSON-serialized to the DLT), a
     * deserialization failure carries the original raw bytes (recovered from
     * the ErrorHandlingDeserializer's header, published as-is).
     *
     * Partition invariant (§9, documented by design review): the default
     * resolver publishes to <source-topic>.DLT at the SAME partition number,
     * which requires the DLT to have at least as many partitions as its
     * source. Trivially true while all topics are broker-auto-created
     * single-partition; must be preserved when topics are provisioned
     * explicitly.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer() {

        Map<String, Object> common = new HashMap<>();
        common.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        common.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        Map<String, Object> jsonConfig = new HashMap<>(common);
        jsonConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        Map<String, Object> bytesConfig = new HashMap<>(common);
        bytesConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, new KafkaTemplate<>(new DefaultKafkaProducerFactory<Object, Object>(bytesConfig)));
        templates.put(Object.class, new KafkaTemplate<>(new DefaultKafkaProducerFactory<Object, Object>(jsonConfig)));

        return new DeadLetterPublishingRecoverer(templates);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {

        // ExponentialBackOffWithMaxRetries, NOT plain ExponentialBackOff -
        // the plain variant has no attempt bound at all (design-review catch),
        // so "3 total attempts" would never actually be enforced.
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BookingEvent> bookingEventContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, BookingEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bookingEventConsumerFactory());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
