package com.skybook.praveen.notificationservice.config;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.common.event.EmailEvent;
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
 * Three consumers (EmailEvent, BookingEvent, CheckInEvent), no producer.
 *
 * Resilience (RESILIENCE_MODULE.md §9): every consumer factory wraps its
 * JsonDeserializer in ErrorHandlingDeserializer (poison-pill guard) and
 * every container factory shares one DefaultErrorHandler - bounded
 * exponential backoff (attempt 1 -> 1s -> attempt 2 -> 2s -> attempt 3)
 * then dead-letter to <source-topic>-dlt (spring-kafka's default suffix;
 * same-partition publishing requires DLT partitions >= source partitions
 * if topics are ever provisioned explicitly).
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private Map<String, Object> consumerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-service");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return config;
    }

    private <T> ConsumerFactory<String, T> consumerFactoryFor(Class<T> eventType) {
        JsonDeserializer<T> json = new JsonDeserializer<>(eventType);
        json.addTrustedPackages("com.skybook.praveen.common.event");
        return new DefaultKafkaConsumerFactory<>(
                consumerConfig(),
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(json));
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

    // ---------------------------------------------------------------
    // EmailEvent (skybook.email.events) - consumed by EmailEventConsumer
    // ---------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, EmailEvent> consumerFactory() {
        return consumerFactoryFor(EmailEvent.class);
    }

    /**
     * Explicit default container factory for the EmailEvent listener. Needed
     * because with multiple ConsumerFactory beans Spring Boot no longer
     * auto-creates "kafkaListenerContainerFactory".
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EmailEvent> kafkaListenerContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, EmailEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    // ---------------------------------------------------------------
    // BookingEvent (skybook.booking.events) - consumed by BookingEventConsumer
    // ---------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, BookingEvent> bookingEventConsumerFactory() {
        return consumerFactoryFor(BookingEvent.class);
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

    // ---------------------------------------------------------------
    // CheckInEvent (skybook.checkin.events) - consumed by CheckInEventConsumer
    // ---------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, CheckInEvent> checkInEventConsumerFactory() {
        return consumerFactoryFor(CheckInEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CheckInEvent> checkInEventContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, CheckInEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(checkInEventConsumerFactory());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
