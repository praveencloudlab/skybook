package com.skybook.praveen.inventoryservice.producer;

import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.InventoryEvent;
import com.skybook.praveen.common.event.InventoryEventType;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryEventProducerTest {

    @Mock
    private KafkaTemplate<String, InventoryEvent> kafkaTemplate;

    private InventoryEventProducer producer;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        // The producer chains whenComplete on the send future
        // (RESILIENCE_MODULE.md §10), so the mock must return one.
        lenient().when(kafkaTemplate.send(anyString(), any(InventoryEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        producer = new InventoryEventProducer(kafkaTemplate);
    }

    private InventoryEvent capturePublishedEvent() {
        ArgumentCaptor<InventoryEvent> captor = ArgumentCaptor.forClass(InventoryEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.INVENTORY_EVENTS), captor.capture());
        return captor.getValue();
    }

    @Test
    void inventoryCreatedPublishesToInventoryTopicWithoutSeatOrBooking() {
        producer.publishInventoryCreated(new FlightInventoryResponse(
                10L, 100L, 1L, "VT-SKB", InventoryStatus.OPEN, 3, 3, 0, 0, 0, 0L, now, now));

        InventoryEvent event = capturePublishedEvent();
        assertThat(event.getType()).isEqualTo(InventoryEventType.INVENTORY_CREATED);
        assertThat(event.getFlightId()).isEqualTo(100L);
        assertThat(event.getSeatNumber()).isNull();
        assertThat(event.getBookingId()).isNull();
        assertThat(event.getDetails()).contains("3 seats").contains("VT-SKB");
    }

    @Test
    void seatHeldCarriesFlightSeatBookingAndExpiry() {
        producer.publishSeatHeld(new SeatHoldResponse(
                5L, 100L, 2L, "12A", 42L, SeatHoldStatus.ACTIVE, now, now.plusMinutes(15)));

        InventoryEvent event = capturePublishedEvent();
        assertThat(event.getType()).isEqualTo(InventoryEventType.SEAT_HELD);
        assertThat(event.getFlightId()).isEqualTo(100L);
        assertThat(event.getSeatNumber()).isEqualTo("12A");
        assertThat(event.getBookingId()).isEqualTo(42L);
        assertThat(event.getDetails()).contains(now.plusMinutes(15).toString());
    }

    @Test
    void seatReleasedCarriesCorrelationFields() {
        producer.publishSeatReleased(new SeatHoldResponse(
                5L, 100L, 2L, "12A", 42L, SeatHoldStatus.RELEASED, now, now.plusMinutes(15)));

        InventoryEvent event = capturePublishedEvent();
        assertThat(event.getType()).isEqualTo(InventoryEventType.SEAT_RELEASED);
        assertThat(event.getFlightId()).isEqualTo(100L);
        assertThat(event.getSeatNumber()).isEqualTo("12A");
        assertThat(event.getBookingId()).isEqualTo(42L);
    }

    @Test
    void seatReservedAndCancelledMapToTheirTypes() {
        SeatReservationResponse reservation = new SeatReservationResponse(
                9L, 100L, 2L, "12A", 42L, 200L, 5L, SeatReservationStatus.RESERVED, now, null);

        producer.publishSeatReserved(reservation);
        assertThat(capturePublishedEvent().getType()).isEqualTo(InventoryEventType.SEAT_RESERVED);
    }

    @Test
    void reservationCancelledMapsToItsType() {
        producer.publishReservationCancelled(new SeatReservationResponse(
                9L, 100L, 2L, "12A", 42L, 200L, 5L, SeatReservationStatus.CANCELLED, now, now));

        InventoryEvent event = capturePublishedEvent();
        assertThat(event.getType()).isEqualTo(InventoryEventType.RESERVATION_CANCELLED);
        assertThat(event.getBookingId()).isEqualTo(42L);
    }

    @Test
    void failedSendLogsAtErrorAndDoesNotThrow() {
        CompletableFuture<SendResult<String, InventoryEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker gone"));
        when(kafkaTemplate.send(anyString(), any(InventoryEvent.class))).thenReturn(failed);

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger producerLogger = (Logger) LoggerFactory.getLogger(InventoryEventProducer.class);
        producerLogger.addAppender(logs);

        try {
            producer.publishSeatHeld(new SeatHoldResponse(
                    5L, 100L, 2L, "12A", 42L, SeatHoldStatus.ACTIVE, now, now.plusMinutes(15)));

            assertThat(logs.list)
                    .as("a broker-side send failure must be visible at ERROR, not silently dropped")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains("Failed to publish");
                    });
        } finally {
            producerLogger.detachAppender(logs);
        }
    }
}
