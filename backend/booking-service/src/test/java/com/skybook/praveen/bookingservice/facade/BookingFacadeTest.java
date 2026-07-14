package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryHoldDetails;
import com.skybook.praveen.bookingservice.client.InventoryReservationDetails;
import com.skybook.praveen.bookingservice.client.InventoryServiceClient;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.SeatUnavailableException;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingFacadeTest {

    @Mock
    private FlightServiceClient flightServiceClient;
    @Mock
    private InventoryServiceClient inventoryServiceClient;
    @Mock
    private BookingService bookingService;
    @Mock
    private BookingEventProducer bookingEventProducer;

    private BookingFacade facade;

    private LocalDateTime departure() {
        return flight.departureTime();
    }

    @BeforeEach
    void setUp() {
        facade = new BookingFacade(flightServiceClient, inventoryServiceClient,
                bookingService, bookingEventProducer);
    }

    private BookingPassengerResponse passenger(long id, String seat) {
        return new BookingPassengerResponse(id, id + 100, "Pax", "Test", "N000" + id,
                TravelClass.ECONOMY, FareType.FLEXI, seat,
                new BigDecimal("100.00"), BigDecimal.ZERO,
                com.skybook.praveen.bookingservice.enums.SeatAssignmentMode.MANUAL, "USD",
                new BigDecimal("100.00"), CheckInStatus.NOT_OPEN);
    }

    private BookingResponse booking(BookingStatus status, String... seats) {
        List<BookingPassengerResponse> passengers = new java.util.ArrayList<>();
        long id = 1;
        for (String seat : seats) {
            passengers.add(passenger(id++, seat));
        }
        return new BookingResponse(7L, "SBFACD", 1L, 10L, status, LocalDateTime.now(),
                new BigDecimal("100.00"), null, passengers, null, null,
                "system", "system", 0L, LocalDateTime.now(), LocalDateTime.now());
    }

    private InventoryHoldDetails hold(String seat) {
        return new InventoryHoldDetails(5L, seat, "ACTIVE", LocalDateTime.now().plusMinutes(15));
    }

    private final FlightDetails flight = new FlightDetails(
            10L, "AI131", "LHR", "DEL",
            LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(7).plusHours(9),
            FlightBookingStatus.SCHEDULED);

    private void stubFlightOk() {
        when(flightServiceClient.getFlight(10L)).thenReturn(flight);
    }

    // ---------------------------------------------------------------
    // createBooking - seat holds
    // ---------------------------------------------------------------

    @Nested
    class CreateWithHolds {

        private final CreateBookingRequest request = new CreateBookingRequest(
                1L, 10L, List.of(), null, null);

        @Test
        void holdsEverySeatThenPublishes() {
            stubFlightOk();
            BookingResponse created = booking(BookingStatus.CREATED, "12A", "12B");
            when(bookingService.createBooking(request, departure())).thenReturn(created);
            when(inventoryServiceClient.holdSeat(eq(10L), anyString(), eq(7L)))
                    .thenAnswer(inv -> Optional.of(hold(inv.getArgument(1))));

            facade.createBooking(request);

            verify(inventoryServiceClient).holdSeat(10L, "12A", 7L);
            verify(inventoryServiceClient).holdSeat(10L, "12B", 7L);
            verify(bookingEventProducer).publishBookingCreated(created, flight);
            verify(bookingService, never()).cancelBooking(anyLong(), anyString());
        }

        @Test
        void flightWithoutInventorySkipsHoldsAfterFirstProbe() {
            stubFlightOk();
            BookingResponse created = booking(BookingStatus.CREATED, "12A", "12B");
            when(bookingService.createBooking(request, departure())).thenReturn(created);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L)).thenReturn(Optional.empty());

            facade.createBooking(request);

            verify(inventoryServiceClient).holdSeat(10L, "12A", 7L);
            verify(inventoryServiceClient, never()).holdSeat(10L, "12B", 7L);
            verify(bookingEventProducer).publishBookingCreated(created, flight);
        }

        @Test
        void seatConflictCompensatesAndCancelsTheBooking() {
            stubFlightOk();
            BookingResponse created = booking(BookingStatus.CREATED, "12A", "12B");
            when(bookingService.createBooking(request, departure())).thenReturn(created);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L)).thenReturn(Optional.of(hold("12A")));
            when(inventoryServiceClient.holdSeat(10L, "12B", 7L))
                    .thenThrow(new SeatUnavailableException(10L, "12B", "already held or reserved"));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(SeatUnavailableException.class);

            // The successful hold is released, the booking cancelled, no event published.
            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
            verify(bookingService).cancelBooking(eq(7L), anyString());
            verify(bookingEventProducer, never()).publishBookingCreated(any(), any());
        }
    }

    // ---------------------------------------------------------------
    // Payment-driven confirmation
    // ---------------------------------------------------------------

    @Nested
    class ConfirmFromPayment {

        @Test
        void confirmsReservesSeatsAndPublishes() {
            BookingResponse confirmed = booking(BookingStatus.CONFIRMED, "12A");
            when(bookingService.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9"))
                    .thenReturn(new BookingService.PaymentConfirmation(confirmed, true));
            when(inventoryServiceClient.reserveSeat(eq(10L), eq("12A"), eq(7L), anyLong()))
                    .thenReturn(Optional.of(new InventoryReservationDetails(9L, "12A", "RESERVED")));

            facade.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9");

            verify(inventoryServiceClient).reserveSeat(10L, "12A", 7L, 1L);
            verify(bookingEventProducer).publishBookingConfirmed(confirmed, null);
        }

        @Test
        void duplicatePaymentEventPublishesNothing() {
            BookingResponse confirmed = booking(BookingStatus.CONFIRMED, "12A");
            when(bookingService.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9"))
                    .thenReturn(new BookingService.PaymentConfirmation(confirmed, false));
            when(inventoryServiceClient.reserveSeat(anyLong(), anyString(), anyLong(), anyLong()))
                    .thenReturn(Optional.of(new InventoryReservationDetails(9L, "12A", "RESERVED")));

            facade.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9");

            verify(bookingEventProducer, never()).publishBookingConfirmed(any(), any());
        }

        @Test
        void reservationHiccupDoesNotFailTheConfirmation() {
            BookingResponse confirmed = booking(BookingStatus.CONFIRMED, "12A");
            when(bookingService.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9"))
                    .thenReturn(new BookingService.PaymentConfirmation(confirmed, true));
            when(inventoryServiceClient.reserveSeat(anyLong(), anyString(), anyLong(), anyLong()))
                    .thenThrow(new SeatUnavailableException(10L, "12A", "already reserved"));

            BookingResponse result = facade.confirmBookingFromPayment(7L, "PAY-2026-K7M4Z9");

            assertThat(result.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(bookingEventProducer).publishBookingConfirmed(confirmed, null);
        }
    }

    // ---------------------------------------------------------------
    // Cancel - inventory cleanup
    // ---------------------------------------------------------------

    @Test
    void cancelReleasesHoldsAndReservationsQuietlyThenPublishes() {
        BookingResponse cancelled = booking(BookingStatus.CANCELLED, "12A", "12B");
        when(bookingService.cancelBooking(7L, "changed plans")).thenReturn(cancelled);

        facade.cancelBooking(7L, "changed plans");

        verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
        verify(inventoryServiceClient).cancelReservationQuietly(eq(10L), eq("12B"), eq(7L), anyString());
        verify(bookingEventProducer).publishBookingCancelled(cancelled, null);
    }
}
