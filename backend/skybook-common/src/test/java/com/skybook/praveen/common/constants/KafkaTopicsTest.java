package com.skybook.praveen.common.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Topic names are a cross-service contract: a producer and a consumer that
 * disagree by one character do not fail the build, they simply never meet.
 * The literals are therefore asserted verbatim rather than compared to each
 * other.
 */
class KafkaTopicsTest {

    @Test
    @DisplayName("every topic constant holds its exact published name")
    void topicNamesAreExact() {
        assertThat(KafkaTopics.EMAIL_EVENTS).isEqualTo("skybook.email.events");
        assertThat(KafkaTopics.BOOKING_EVENTS).isEqualTo("skybook.booking.events");
        assertThat(KafkaTopics.PAYMENT_EVENTS).isEqualTo("skybook.payment.events");
        assertThat(KafkaTopics.FLIGHT_EVENTS).isEqualTo("skybook.flight.events");
        assertThat(KafkaTopics.CHECKIN_EVENTS).isEqualTo("skybook.checkin.events");
        assertThat(KafkaTopics.INVENTORY_EVENTS).isEqualTo("skybook.inventory.events");
    }

    @Test
    @DisplayName("no two topics share a name - a copy-paste slip would cross the streams")
    void topicNamesAreDistinct() {
        List<String> topics = List.of(
                KafkaTopics.EMAIL_EVENTS,
                KafkaTopics.BOOKING_EVENTS,
                KafkaTopics.PAYMENT_EVENTS,
                KafkaTopics.FLIGHT_EVENTS,
                KafkaTopics.CHECKIN_EVENTS,
                KafkaTopics.INVENTORY_EVENTS);
        assertThat(topics).doesNotHaveDuplicates().hasSize(6);
    }

    @Test
    @DisplayName("every topic follows the skybook.<domain>.events naming rule")
    void topicNamesFollowTheNamingRule() {
        List<String> declared = Arrays.stream(KafkaTopics.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(KafkaTopicsTest::valueOf)
                .toList();

        assertThat(declared).hasSize(6);
        assertThat(declared).allSatisfy(topic -> assertThat(topic).matches("skybook\\.[a-z]+\\.events"));
    }

    @Test
    @DisplayName("the constants are public static final - callers can inline them safely")
    void constantsArePublicStaticFinal() {
        Arrays.stream(KafkaTopics.class.getDeclaredFields())
                .filter(f -> f.getType() == String.class)
                .forEach(f -> {
                    assertThat(Modifier.isPublic(f.getModifiers())).isTrue();
                    assertThat(Modifier.isStatic(f.getModifiers())).isTrue();
                    assertThat(Modifier.isFinal(f.getModifiers())).isTrue();
                });
    }

    @Test
    @DisplayName("KafkaTopics is a final, non-instantiable constants holder")
    void isAFinalUtilityClass() throws Exception {
        assertThat(Modifier.isFinal(KafkaTopics.class.getModifiers())).isTrue();
        Constructor<KafkaTopics> constructor = KafkaTopics.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isNotNull();
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }
}
