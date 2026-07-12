package com.skybook.praveen.bookingservice.config;

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
 * Sprint 6: booking-service becomes a consumer too - PaymentEvent from
 * skybook.payment.events. Explicitly named container factory referenced by
 * the listener (standing lesson from notification-service's setup).
 *
 * Resilience (RESILIENCE_MODULE.md §9): ErrorHandlingDeserializer guards
 * against poison pills, and the container factory carries a
 * DefaultErrorHandler with bounded exponential backoff - attempt 1 -> 1s ->
 * attempt 2 -> 2s -> attempt 3 -> skybook.payment.events-dlt (spring-kafka's
 * default "-dlt" suffix; the recoverer publishes to the same partition
 * number, so any explicit DLT provisioning must match source partitions).
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, PaymentEvent> paymentEventConsumerFactory() {

        JsonDeserializer<PaymentEvent> json = new JsonDeserializer<>(PaymentEvent.class);
        json.addTrustedPackages("com.skybook.praveen.common.event");

        ErrorHandlingDeserializer<PaymentEvent> value = new ErrorHandlingDeserializer<>(json);

        Map<String, Object> config = new HashMap<>();

        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "booking-service");
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
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> paymentEventContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentEventConsumerFactory());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
