package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryHoldDetails;
import com.skybook.praveen.bookingservice.client.InventoryReservationDetails;
import com.skybook.praveen.bookingservice.client.InventoryServiceClient;
import com.skybook.praveen.bookingservice.domain.SeatAssignmentResult;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.PassengerBookingDetail;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
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

    @org.mockito.Captor
    private org.mockito.ArgumentCaptor<List<SeatAssignmentResult>> assignmentsCaptor;

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

    private InventoryHoldDetails hold(String seat, String mode, String listed, String charged) {
        return new InventoryHoldDetails(5L, seat, mode,
                new BigDecimal(listed), new BigDecimal(charged),
                "ACTIVE", LocalDateTime.now().plusMinutes(15));
    }

    private PassengerBookingDetail detail(String seatNumber) {
        return new PassengerBookingDetail(
                "Mr", "Pax", null, "Test",
                java.time.LocalDate.of(1990, 1, 1), "MALE", "GBR",
                "P1234567", java.time.LocalDate.of(2032, 1, 1),
                "pax@example.com", "+441234567890",
                TravelClass.ECONOMY, FareType.FLEXI, seatNumber);
    }

    private final FlightDetails flight = new FlightDetails(
            10L, "AI131", "LHR", "DEL",
            LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(7).plusHours(9),
            FlightBookingStatus.SCHEDULED);

    private void stubFlightOk() {
        when(flightServiceClient.getFlight(10L)).thenReturn(flight);
    }

    // ---------------------------------------------------------------
    // createBooking - draft -> hold -> finalize (§5.1)
    // ---------------------------------------------------------------

    @Nested
    class CreateDraftHoldFinalize {

        // Two manual seats; the draft persists passengers seatless in this order.
        private final CreateBookingRequest request = new CreateBookingRequest(
                1L, 10L, List.of(detail("12A"), detail("12B")), null, null);

        private final BookingResponse draft = booking(BookingStatus.DRAFT, (String) null, (String) null);

        @Test
        void holdsEverySeatFinalizesThenPublishes() {
            stubFlightOk();
            BookingResponse created = booking(BookingStatus.CREATED, "12A", "12B");
            when(bookingService.createDraftBooking(request, departure())).thenReturn(draft);
            when(inventoryServiceClient.holdSeat(eq(10L), anyString(), eq(7L), anyLong(), eq(TravelClass.ECONOMY)))
                    .thenAnswer(inv -> Optional.of(hold(inv.getArgument(1), "MANUAL", "12.00", "12.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any())).thenReturn(created);

            facade.createBooking(request);

            verify(inventoryServiceClient).holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY);
            verify(inventoryServiceClient).holdSeat(10L, "12B", 7L, 2L, TravelClass.ECONOMY);
            verify(bookingService).finalizeSeatAssignments(eq(7L), assignmentsCaptor.capture());
            List<SeatAssignmentResult> assignments = assignmentsCaptor.getValue();
            assertThat(assignments).hasSize(2);
            assertThat(assignments.get(0).seatNumber()).isEqualTo("12A");
            assertThat(assignments.get(0).chargedSurcharge()).isEqualByComparingTo("12.00");
            assertThat(assignments.get(0).mode()).isEqualTo(SeatAssignmentMode.MANUAL);
            // The FINALIZED response is announced, never the draft.
            verify(bookingEventProducer).publishBookingCreated(created, flight);
            verify(bookingService, never()).cancelBooking(anyLong(), anyString());
        }

        @Test
        void blankSeatGoesThroughTheAtomicAutoHold() {
            stubFlightOk();
            CreateBookingRequest autoRequest = new CreateBookingRequest(
                    1L, 10L, List.of(detail(null)), null, null);
            BookingResponse autoDraft = booking(BookingStatus.DRAFT, (String) null);
            BookingResponse created = booking(BookingStatus.CREATED, "20B");
            when(bookingService.createDraftBooking(autoRequest, departure())).thenReturn(autoDraft);
            when(inventoryServiceClient.autoHoldSeat(10L, 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("20B", "AUTO", "0.00", "0.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any())).thenReturn(created);

            facade.createBooking(autoRequest);

            verify(inventoryServiceClient).autoHoldSeat(10L, 7L, 1L, TravelClass.ECONOMY);
            verify(inventoryServiceClient, never()).holdSeat(anyLong(), anyString(), anyLong(), anyLong(), any());
            verify(bookingService).finalizeSeatAssignments(eq(7L), assignmentsCaptor.capture());
            assertThat(assignmentsCaptor.getValue().get(0).mode()).isEqualTo(SeatAssignmentMode.AUTO);
            assertThat(assignmentsCaptor.getValue().get(0).chargedSurcharge()).isEqualByComparingTo("0.00");
            verify(bookingEventProducer).publishBookingCreated(created, flight);
        }

        @Test
        void flightWithoutInventoryFinalizesRequestedSeatsUnpriced() {
            stubFlightOk();
            BookingResponse created = booking(BookingStatus.CREATED, "12A", "12B");
            when(bookingService.createDraftBooking(request, departure())).thenReturn(draft);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.empty());
            when(bookingService.finalizeSeatAssignments(eq(7L), any())).thenReturn(created);

            facade.createBooking(request);

            // First probe says "no inventory" - nothing more is held, the
            // requested seats finalize unpriced (hold-if-exists policy).
            verify(inventoryServiceClient, never()).holdSeat(eq(10L), eq("12B"), anyLong(), anyLong(), any());
            verify(bookingService).finalizeSeatAssignments(eq(7L), assignmentsCaptor.capture());
            List<SeatAssignmentResult> assignments = assignmentsCaptor.getValue();
            assertThat(assignments).hasSize(2);
            assertThat(assignments.get(0).seatNumber()).isEqualTo("12A");
            assertThat(assignments.get(0).chargedSurcharge()).isEqualByComparingTo("0.00");
            verify(bookingEventProducer).publishBookingCreated(created, flight);
        }

        @Test
        void seatConflictCompensatesAndCancelsTheDraft() {
            stubFlightOk();
            when(bookingService.createDraftBooking(request, departure())).thenReturn(draft);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("12A", "MANUAL", "12.00", "12.00")));
            when(inventoryServiceClient.holdSeat(10L, "12B", 7L, 2L, TravelClass.ECONOMY))
                    .thenThrow(new SeatUnavailableException(10L, "12B", "already held or reserved"));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(SeatUnavailableException.class);

            // The successful hold is released, the DRAFT cancelled, nothing
            // finalized, no event published.
            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
            verify(bookingService).cancelBooking(eq(7L), anyString());
            verify(bookingService, never()).finalizeSeatAssignments(anyLong(), any());
            verify(bookingEventProducer, never()).publishBookingCreated(any(), any());
        }

        @Test
        void finalizeFailureReleasesHoldsAndCancelsTheDraft() {
            stubFlightOk();
            when(bookingService.createDraftBooking(request, departure())).thenReturn(draft);
            when(inventoryServiceClient.holdSeat(eq(10L), anyString(), eq(7L), anyLong(), eq(TravelClass.ECONOMY)))
                    .thenAnswer(inv -> Optional.of(hold(inv.getArgument(1), "MANUAL", "12.00", "12.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any()))
                    .thenThrow(new IllegalStateException("only a DRAFT can be finalized"));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalStateException.class);

            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12B"), eq(7L), anyString());
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
