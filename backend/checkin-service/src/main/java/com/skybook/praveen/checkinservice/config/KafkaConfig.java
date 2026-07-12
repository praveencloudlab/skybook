package com.skybook.praveen.checkinservice.config;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.CheckInEvent;
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
 * Producer (CheckInEvent out) and consumer (BookingEvent in) in one config -
 * same shape as payment-service's KafkaConfig, which sits in the identical
 * position (consumes BookingEvent, produces its own event type).
 *
 * Resilience (RESILIENCE_MODULE.md §9): ErrorHandlingDeserializer guards
 * against poison pills; DefaultErrorHandler with bounded exponential
 * backoff - attempt 1 -> 1s -> attempt 2 -> 2s -> attempt 3 ->
 * skybook.booking.events-dlt (spring-kafka's default "-dlt" suffix; the
 * recoverer publishes to the same partition number, so explicit DLT
 * provisioning must match source partitions).
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ---------------------------------------------------------------
    // Producer: CheckInEvent -> skybook.checkin.events
    // ---------------------------------------------------------------

    @Bean
    public ProducerFactory<String, CheckInEvent> producerFactory() {

        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, CheckInEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ---------------------------------------------------------------
    // Consumer: BookingEvent <- skybook.booking.events
    // ---------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, BookingEvent> bookingEventConsumerFactory() {

        JsonDeserializer<BookingEvent> json = new JsonDeserializer<>(BookingEvent.class);
        json.addTrustedPackages("com.skybook.praveen.common.event");

        ErrorHandlingDeserializer<BookingEvent> value = new ErrorHandlingDeserializer<>(json);

        Map<String, Object> config = new HashMap<>();

        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "checkin-service");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new DefaultKafkaConsumerFactory<>(
                config,
                new StringDeserializer(),
                value
        );
    }

    /** See payment-service's KafkaConfig for the two-template reasoning (JSON vs raw-bytes DLT payloads). */
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
