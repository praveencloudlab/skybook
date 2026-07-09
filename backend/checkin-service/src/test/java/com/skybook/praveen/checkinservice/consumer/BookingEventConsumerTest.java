package com.skybook.praveen.checkinservice.consumer;

import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.facade.CheckInFacade;
import com.skybook.praveen.checkinservice.service.CheckInService;
import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BookingEventConsumerTest {

    @Mock
    private CheckInService checkInService;
    @Mock
    private CheckInFacade checkInFacade;

    private BookingEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BookingEventConsumer(checkInService, checkInFacade);
    }

    private BookingEventPassenger passenger(Long bookingPassengerId) {
        return BookingEventPassenger.builder()
                .bookingPassengerId(bookingPassengerId)
                .name("Test Passenger")
                .seatNumber("12B")
                .travelClass("ECONOMY")
                .fareType("FLEXI")
                .fare(new BigDecimal("100.00"))
                .build();
    }

    private BookingEvent event(BookingEventType type, List<BookingEventPassenger> passengers) {
        return BookingEvent.builder()
                .type(type)
                .bookingId(42L)
                .bookingReference("SBTEST")
                .flightId(7L)
                .flightNumber("BA178")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime("2026-07-08 18:00")
                .passengers(passengers)
                .build();
    }

    @Test
    void confirmedEventCreatesOneCheckInPerPassenger() {
        consumer.consume(event(BookingEventType.CONFIRMED, List.of(passenger(100L), passenger(101L))));

        verify(checkInService, times(2))
                .createCheckIn(any(CreateCheckInRequest.class), org.mockito.ArgumentMatchers.eq("KAFKA"),
                        org.mockito.ArgumentMatchers.eq("BOOKING_EVENT"), org.mockito.ArgumentMatchers.eq("SBTEST"));
    }

    @Test
    void confirmedEventBuildsTheRequestFromEventAndPassengerFields() {
        consumer.consume(event(BookingEventType.CONFIRMED, List.of(passenger(100L))));

        ArgumentCaptor<CreateCheckInRequest> captor = ArgumentCaptor.forClass(CreateCheckInRequest.class);
        verify(checkInService).createCheckIn(captor.capture(), anyString(), anyString(), anyString());

        CreateCheckInRequest request = captor.getValue();
        assertThat(request.bookingId()).isEqualTo(42L);
        assertThat(request.bookingReference()).isEqualTo("SBTEST");
        assertThat(request.bookingPassengerId()).isEqualTo(100L);
        assertThat(request.flightId()).isEqualTo(7L);
        assertThat(request.flightNumber()).isEqualTo("BA178");
        assertThat(request.originAirportCode()).isEqualTo("LHR");
        assertThat(request.destinationAirportCode()).isEqualTo("JFK");
        assertThat(request.departureTime()).isEqualTo(LocalDateTime.of(2026, 7, 8, 18, 0));
        assertThat(request.passengerName()).isEqualTo("Test Passenger");
        assertThat(request.seatNumber()).isEqualTo("12B");
        // documentVerified defaults true - booking-service already validated
        // passport data before a booking can reach CONFIRMED.
        assertThat(request.documentVerified()).isTrue();
    }

    @Test
    void confirmedEventWithoutBookingIdIsSkippedEntirely() {
        BookingEvent event = event(BookingEventType.CONFIRMED, List.of(passenger(100L)));
        event.setBookingId(null);

        consumer.consume(event);

        verifyNoInteractions(checkInService, checkInFacade);
    }

    @Test
    void confirmedEventWithoutPassengersIsSkippedEntirely() {
        consumer.consume(event(BookingEventType.CONFIRMED, null));

        verifyNoInteractions(checkInService, checkInFacade);
    }

    @Test
    void passengerMissingBookingPassengerIdIsSkippedButOthersStillProcess() {
        BookingEventPassenger noId = passenger(null);
        consumer.consume(event(BookingEventType.CONFIRMED, List.of(noId, passenger(101L))));

        verify(checkInService, times(1))
                .createCheckIn(any(CreateCheckInRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    void malformedDepartureTimeParsesAsNullRatherThanThrowing() {
        BookingEvent event = event(BookingEventType.CONFIRMED, List.of(passenger(100L)));
        event.setDepartureTime("not-a-real-date");

        consumer.consume(event);

        ArgumentCaptor<CreateCheckInRequest> captor = ArgumentCaptor.forClass(CreateCheckInRequest.class);
        verify(checkInService).createCheckIn(captor.capture(), anyString(), anyString(), anyString());
        assertThat(captor.getValue().departureTime()).isNull();
    }

    @Test
    void cancelledEventDelegatesToTheFacade() {
        consumer.consume(event(BookingEventType.CANCELLED, null));

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(checkInFacade).cancelForBooking(org.mockito.ArgumentMatchers.eq(42L), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("SBTEST");
    }

    @Test
    void createdCompletedAndExpiredEventsAreIgnored() {
        consumer.consume(event(BookingEventType.CREATED, null));
        consumer.consume(event(BookingEventType.COMPLETED, null));
        consumer.consume(event(BookingEventType.EXPIRED, null));

        verifyNoInteractions(checkInService, checkInFacade);
    }
}
