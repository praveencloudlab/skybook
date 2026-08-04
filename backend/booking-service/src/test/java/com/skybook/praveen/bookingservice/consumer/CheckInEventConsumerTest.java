package com.skybook.praveen.bookingservice.consumer;

import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.exception.BookingNotFoundException;
import com.skybook.praveen.bookingservice.service.BookingService;
import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.common.event.CheckInEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * This mirror is what arms the "a checked-in traveller cannot be cancelled
 * online" guard - if it maps an event to the wrong status, or drops the seat
 * a reissued pass moved the passenger to, a live seat gets released under
 * someone about to board.
 */
@ExtendWith(MockitoExtension.class)
class CheckInEventConsumerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private CheckInEventConsumer consumer;

    private static CheckInEvent event(CheckInEventType type, Long bookingId, Long rowId, String seat) {
        return CheckInEvent.builder()
                .type(type)
                .checkInId(300L)
                .bookingId(bookingId)
                .bookingReference("SB1234")
                .bookingPassengerId(rowId)
                .passengerName("Ann Blake")
                .seatNumber(seat)
                .flightId(9L)
                .departureTime(LocalDateTime.now().plusDays(1))
                .occurredAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("event type to read-model status")
    class StatusMapping {

        @ParameterizedTest(name = "{0} mirrors as {1}")
        @CsvSource({
                "PASSENGER_CHECKED_IN, CHECKED_IN",
                "BOARDING_PASS_GENERATED, CHECKED_IN",
                "PASSENGER_BOARDED, BOARDED",
                "PASSENGER_NO_SHOW, NO_SHOW",
                "PASSENGER_CHECKIN_CANCELLED, CLOSED"
        })
        @DisplayName("every check-in event type maps onto exactly one mirrored status")
        void everyTypeMapsToItsStatus(CheckInEventType type, CheckInStatus expected) {
            consumer.consume(event(type, 77L, 11L, "12A"));

            verify(bookingService).applyCheckInStatus(77L, 11L, expected, "12A");
        }

        @Test
        @DisplayName("a reissued boarding pass carries the NEW seat - the mirror must follow it")
        void aReissuedPassCarriesTheNewSeat() {
            consumer.consume(event(CheckInEventType.BOARDING_PASS_GENERATED, 77L, 11L, "20F"));

            verify(bookingService).applyCheckInStatus(77L, 11L, CheckInStatus.CHECKED_IN, "20F");
        }

        @Test
        @DisplayName("an event without a seat mirrors the status alone, it does not blank the seat by accident")
        void anEventWithoutASeatStillMirrorsTheStatus() {
            consumer.consume(event(CheckInEventType.PASSENGER_NO_SHOW, 77L, 11L, null));

            verify(bookingService).applyCheckInStatus(77L, 11L, CheckInStatus.NO_SHOW, null);
        }
    }

    @Nested
    @DisplayName("events the mirror cannot act on")
    class Unusable {

        @Test
        @DisplayName("a typeless event is skipped rather than guessed at")
        void aTypelessEventIsSkipped() {
            consumer.consume(event(null, 77L, 11L, "12A"));

            verifyNoInteractions(bookingService);
        }

        @Test
        @DisplayName("without a booking id there is nothing to mirror onto")
        void withoutABookingIdNothingIsMirrored() {
            consumer.consume(event(CheckInEventType.PASSENGER_CHECKED_IN, null, 11L, "12A"));

            verify(bookingService, never()).applyCheckInStatus(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("without a passenger row id the event names no traveller - skipped")
        void withoutAPassengerRowIdNothingIsMirrored() {
            consumer.consume(event(CheckInEventType.PASSENGER_CHECKED_IN, 77L, null, "12A"));

            verify(bookingService, never()).applyCheckInStatus(anyLong(), anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("failure containment")
    class FailureContainment {

        @Test
        @DisplayName("a booking-side failure is logged, never rethrown onto the check-in topic")
        void aBookingSideFailureNeverPoisonsTheTopic() {
            // Rethrowing here would redeliver forever; checkin-service stays the source of truth.
            doThrow(new BookingNotFoundException(77L)).when(bookingService)
                    .applyCheckInStatus(eq(77L), eq(11L), any(), any());

            assertThatCode(() -> consumer.consume(
                    event(CheckInEventType.PASSENGER_CHECKED_IN, 77L, 11L, "12A")))
                    .doesNotThrowAnyException();
        }
    }
}
