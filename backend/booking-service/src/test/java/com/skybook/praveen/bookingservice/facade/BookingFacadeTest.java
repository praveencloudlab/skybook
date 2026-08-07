package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryCabinDetails;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
    private com.skybook.praveen.bookingservice.repository.BookingRepository bookingRepository;
    @Mock
    private BookingEventProducer bookingEventProducer;
    @Mock
    private com.skybook.praveen.bookingservice.repository.FareAlertRepository fareAlertRepository;

    @org.mockito.Captor
    private org.mockito.ArgumentCaptor<List<SeatAssignmentResult>> assignmentsCaptor;

    private BookingFacade facade;

    private LocalDateTime departure() {
        return flight.departureTime();
    }

    @BeforeEach
    void setUp() {
        // Real FareCalculator - pure/deterministic, already unit-tested.
        facade = new BookingFacade(flightServiceClient, inventoryServiceClient,
                bookingService, bookingRepository, bookingEventProducer,
                new com.skybook.praveen.bookingservice.domain.FareCalculator(
                        java.time.Clock.fixed(java.time.Instant.parse("2030-06-04T09:00:00Z"), java.time.ZoneOffset.UTC)),
                fareAlertRepository,
                new com.skybook.praveen.bookingservice.domain.CancellationPolicy(
                        new BigDecimal("30"), 72, 24, 2, 6));
    }

    private BookingPassengerResponse passenger(long id, String seat) {
        return new BookingPassengerResponse(id, id + 100, 0, 10L, "Pax", "Test", "N000" + id,
                "Mr", "MALE", java.time.LocalDate.of(1990, 1, 1), "GBR", java.time.LocalDate.of(2032, 1, 1),
                TravelClass.ECONOMY, FareType.FLEXI, seat,
                new BigDecimal("100.00"), BigDecimal.ZERO, 0, BigDecimal.ZERO,
                com.skybook.praveen.bookingservice.enums.SeatAssignmentMode.MANUAL, "USD",
                new BigDecimal("100.00"), CheckInStatus.NOT_OPEN, false, "ADULT");
    }

    private BookingResponse booking(BookingStatus status, String... seats) {
        List<BookingPassengerResponse> passengers = new java.util.ArrayList<>();
        long id = 1;
        for (String seat : seats) {
            passengers.add(passenger(id++, seat));
        }
        return new BookingResponse(7L, "SBFACD", 1L, 10L,
                List.of(new com.skybook.praveen.bookingservice.dto.response.BookingSegmentResponse(1L, 0, 10L, "UPCOMING")),
                status, LocalDateTime.now(),
                new BigDecimal("100.00"), null, null, passengers, null, null, List.of(),
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
                TravelClass.ECONOMY, FareType.FLEXI, seatNumber, null, null, null, null);
    }

    private final FlightDetails flight = new FlightDetails(
            10L, "AI131", "LHR", "DEL",
            LocalDateTime.of(2030, 7, 16, 10, 0), LocalDateTime.of(2030, 7, 16, 19, 0),
            null, null, FlightBookingStatus.SCHEDULED);

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
                1L, 10L, null, null, List.of(detail("12A"), detail("12B")), null, null);

        private final BookingResponse draft = booking(BookingStatus.DRAFT, (String) null, (String) null);

        @Test
        void aRetryWithTheSameKeyReplaysTheBookingWithoutBookingAgain() {
            // IDEMPOTENCY §3.3: the reported-shape defect - one press produced
            // two bookings, two seat holds, two charges. A retry carries the
            // same key; the facade must return the ORIGINAL booking without
            // touching flight-service, inventory, or the payment path.
            BookingResponse original = booking(BookingStatus.CONFIRMED, "12A", "12B");
            com.skybook.praveen.bookingservice.entity.Booking existing =
                    new com.skybook.praveen.bookingservice.entity.Booking();
            existing.setId(7L);
            // A null stored fingerprint (legacy row) replays by key alone; a
            // MATCHING fingerprint would too. Either way this identical retry
            // must come straight back as the original.
            when(bookingRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
            when(bookingService.getBookingById(7L)).thenReturn(original);

            BookingResponse result = facade.createBooking(request, "key-1");

            assertThat(result).isSameAs(original);
            verifyNoInteractions(flightServiceClient, inventoryServiceClient);
            verify(bookingService, never()).createDraftBooking(any(), any(), any(), any(), any());
            verify(bookingEventProducer, never()).publishBookingCreated(any(), any());
        }

        @Test
        void aKeyReusedForADifferentTripIsRefused() {
            com.skybook.praveen.bookingservice.entity.Booking existing =
                    new com.skybook.praveen.bookingservice.entity.Booking();
            existing.setId(7L);
            existing.setIdempotencyFingerprint("a-different-trips-fingerprint");
            when(bookingRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> facade.createBooking(request, "key-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different booking");
            verifyNoInteractions(flightServiceClient);
        }

        @Test
        void holdsEverySeatFinalizesThenPublishes() {
            stubFlightOk();
            BookingResponse created = booking(BookingStatus.CREATED, "12A", "12B");
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any())).thenReturn(draft);
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
            // Fare-family entitlement: the fixture passengers are FLEXI, whose
            // seat picks are free - listed 12.00 stays on record, charged is 0.
            assertThat(assignments.get(0).listedSurcharge()).isEqualByComparingTo("12.00");
            assertThat(assignments.get(0).chargedSurcharge()).isEqualByComparingTo("0.00");
            assertThat(assignments.get(0).mode()).isEqualTo(SeatAssignmentMode.MANUAL);
            // The FINALIZED response is announced, never the draft.
            verify(bookingEventProducer).publishBookingCreated(created, List.of(flight));
            verify(bookingService, never()).cancelBooking(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        void blankSeatGoesThroughTheAtomicAutoHold() {
            stubFlightOk();
            CreateBookingRequest autoRequest = new CreateBookingRequest(
                    1L, 10L, null, null, List.of(detail(null)), null, null);
            BookingResponse autoDraft = booking(BookingStatus.DRAFT, (String) null);
            BookingResponse created = booking(BookingStatus.CREATED, "20B");
            when(bookingService.createDraftBooking(eq(autoRequest), any(), any(), any(), any())).thenReturn(autoDraft);
            when(inventoryServiceClient.autoHoldSeat(10L, 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("20B", "AUTO", "0.00", "0.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any())).thenReturn(created);

            facade.createBooking(autoRequest);

            verify(inventoryServiceClient).autoHoldSeat(10L, 7L, 1L, TravelClass.ECONOMY);
            verify(inventoryServiceClient, never()).holdSeat(anyLong(), anyString(), anyLong(), anyLong(), any());
            verify(bookingService).finalizeSeatAssignments(eq(7L), assignmentsCaptor.capture());
            assertThat(assignmentsCaptor.getValue().get(0).mode()).isEqualTo(SeatAssignmentMode.AUTO);
            assertThat(assignmentsCaptor.getValue().get(0).chargedSurcharge()).isEqualByComparingTo("0.00");
            verify(bookingEventProducer).publishBookingCreated(created, List.of(flight));
        }

        @Test
        void flightWithoutInventoryFinalizesRequestedSeatsUnpriced() {
            stubFlightOk();
            BookingResponse created = booking(BookingStatus.CREATED, "12A", "12B");
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any())).thenReturn(draft);
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
            verify(bookingEventProducer).publishBookingCreated(created, List.of(flight));
        }

        @Test
        void noInventoryAfterASuccessfulHoldCompensatesInsteadOfLeaking() {
            // "No inventory" is a per-flight fact - arriving AFTER a hold
            // succeeded on the same flight is an inconsistent downstream
            // state. The earlier hold must be released and the draft
            // cancelled, never finalized unpriced (review hardening).
            stubFlightOk();
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any())).thenReturn(draft);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("12A", "MANUAL", "12.00", "12.00")));
            when(inventoryServiceClient.holdSeat(10L, "12B", 7L, 2L, TravelClass.ECONOMY))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inconsistent");

            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
            verify(bookingService).cancelBooking(eq(7L), anyString(), eq(100));
            verify(bookingService, never()).finalizeSeatAssignments(anyLong(), any());
            verify(bookingEventProducer, never()).publishBookingCreated(any(), any());
        }

        @Test
        void seatConflictCompensatesAndCancelsTheDraft() {
            stubFlightOk();
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any())).thenReturn(draft);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("12A", "MANUAL", "12.00", "12.00")));
            when(inventoryServiceClient.holdSeat(10L, "12B", 7L, 2L, TravelClass.ECONOMY))
                    .thenThrow(new SeatUnavailableException(10L, "12B", "already held or reserved"));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(SeatUnavailableException.class);

            // The successful hold is released, the DRAFT cancelled, nothing
            // finalized, no event published.
            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
            verify(bookingService).cancelBooking(eq(7L), anyString(), eq(100));
            verify(bookingService, never()).finalizeSeatAssignments(anyLong(), any());
            verify(bookingEventProducer, never()).publishBookingCreated(any(), any());
        }

        @Test
        void finalizeFailureReleasesHoldsAndCancelsTheDraft() {
            stubFlightOk();
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any())).thenReturn(draft);
            when(inventoryServiceClient.holdSeat(eq(10L), anyString(), eq(7L), anyLong(), eq(TravelClass.ECONOMY)))
                    .thenAnswer(inv -> Optional.of(hold(inv.getArgument(1), "MANUAL", "12.00", "12.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any()))
                    .thenThrow(new IllegalStateException("only a DRAFT can be finalized"));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalStateException.class);

            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12B"), eq(7L), anyString());
            verify(bookingService).cancelBooking(eq(7L), anyString(), eq(100));
            verify(bookingEventProducer, never()).publishBookingCreated(any(), any());
        }
    }

    // ---------------------------------------------------------------
    // Round trip - one PNR, two segments, one saga (ROUND_TRIP_MODULE.md §5)
    // ---------------------------------------------------------------

    @Nested
    class RoundTrip {

        private final FlightDetails returnFlight = new FlightDetails(
                20L, "AI132", "DEL", "LHR",
                LocalDateTime.of(2030, 7, 23, 10, 0), LocalDateTime.of(2030, 7, 23, 19, 0),
                null, null, FlightBookingStatus.SCHEDULED);

        // One traveller with a manual outbound seat pick; return auto-assigns.
        private final CreateBookingRequest request = new CreateBookingRequest(
                1L, 10L, 20L, null, List.of(detail("12A")), null, null);

        private BookingPassengerResponse row(long id, int segmentIndex, Long flightId, String seat) {
            return new BookingPassengerResponse(id, 100L, segmentIndex, flightId, "Pax", "Test", "N0001",
                    "Mr", "MALE", java.time.LocalDate.of(1990, 1, 1), "GBR", java.time.LocalDate.of(2032, 1, 1),
                    TravelClass.ECONOMY, FareType.FLEXI, seat,
                    new BigDecimal("100.00"), BigDecimal.ZERO, 0, BigDecimal.ZERO,
                    SeatAssignmentMode.MANUAL, "USD",
                    new BigDecimal("100.00"), CheckInStatus.NOT_OPEN, false, "ADULT");
        }

        /** Segment-major rows: row 1 = traveller on the outbound, row 2 = same traveller on the return. */
        private BookingResponse roundTripBooking(BookingStatus status, String outboundSeat, String returnSeat) {
            return new BookingResponse(7L, "SBFACD", 1L, 10L,
                    List.of(new com.skybook.praveen.bookingservice.dto.response.BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                            new com.skybook.praveen.bookingservice.dto.response.BookingSegmentResponse(2L, 1, 20L, "UPCOMING")),
                    status, LocalDateTime.now(),
                    new BigDecimal("200.00"), null, null,
                    List.of(row(1L, 0, 10L, outboundSeat), row(2L, 1, 20L, returnSeat)),
                    null, null, List.of(), "system", "system", 0L, LocalDateTime.now(), LocalDateTime.now());
        }

        @Test
        void holdsAcrossBothFlightsAndFinalizesOnce() {
            stubFlightOk();
            when(flightServiceClient.getFlight(20L)).thenReturn(returnFlight);
            BookingResponse draft = roundTripBooking(BookingStatus.DRAFT, null, null);
            BookingResponse created = roundTripBooking(BookingStatus.CREATED, "12A", "20B");
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any()))
                    .thenReturn(draft);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("12A", "MANUAL", "12.00", "12.00")));
            when(inventoryServiceClient.autoHoldSeat(20L, 7L, 2L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("20B", "AUTO", "0.00", "0.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any())).thenReturn(created);

            BookingResponse result = facade.createBooking(request);

            // ONE finalize, ONE event, ONE booking - the outbound seat manual,
            // the return auto on ITS OWN flight.
            assertThat(result.segments()).hasSize(2);
            verify(bookingService).finalizeSeatAssignments(eq(7L), assignmentsCaptor.capture());
            assertThat(assignmentsCaptor.getValue()).hasSize(2);
            assertThat(assignmentsCaptor.getValue().get(1).seatNumber()).isEqualTo("20B");
            verify(bookingEventProducer).publishBookingCreated(created, List.of(flight, returnFlight));
        }

        @Test
        void returnFlightHoldFailureReleasesOutboundHoldsToo() {
            // THE critical saga test (ROUND_TRIP_MODULE.md §10 step 2): the
            // second flight's first hold fails after the first flight's holds
            // succeeded - every hold on EITHER flight must be released and
            // the draft cancelled. All-or-nothing.
            stubFlightOk();
            when(flightServiceClient.getFlight(20L)).thenReturn(returnFlight);
            BookingResponse draft = roundTripBooking(BookingStatus.DRAFT, null, null);
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any()))
                    .thenReturn(draft);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("12A", "MANUAL", "12.00", "12.00")));
            when(inventoryServiceClient.autoHoldSeat(20L, 7L, 2L, TravelClass.ECONOMY))
                    .thenThrow(new SeatUnavailableException(20L, "ANY", "cabin full"));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(SeatUnavailableException.class);

            verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
            verify(bookingService).cancelBooking(eq(7L), anyString(), eq(100));
            verify(bookingService, never()).finalizeSeatAssignments(anyLong(), any());
            verify(bookingEventProducer, never()).publishBookingCreated(any(), any());
        }

        @Test
        void aCancelledReturnFlightIsRejectedBeforeAnyDraft() {
            stubFlightOk();
            when(flightServiceClient.getFlight(20L)).thenReturn(new FlightDetails(
                    20L, "AI132", "DEL", "LHR",
                    returnFlight.departureTime(), returnFlight.arrivalTime(),
                    null, null, FlightBookingStatus.CANCELLED));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cancelled return flight");

            verify(bookingService, never()).createDraftBooking(any(), any(), any(), any(), any());
        }

        @Test
        void returnDepartingBeforeOutboundArrivalIsRejectedBeforeAnyDraft() {
            stubFlightOk();
            FlightDetails tooEarly = new FlightDetails(20L, "AI132", "DEL", "LHR",
                    flight.arrivalTime().minusHours(2), flight.arrivalTime().plusHours(6),
                    null, null, FlightBookingStatus.SCHEDULED);
            when(flightServiceClient.getFlight(20L)).thenReturn(tooEarly);

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("after the outbound arrives");

            verify(bookingService, never()).createDraftBooking(any(), any(), any(), any(), any());
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
            verify(bookingEventProducer).publishBookingConfirmed(eq(confirmed), any());
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
            verify(bookingEventProducer).publishBookingConfirmed(eq(confirmed), any());
        }
    }

    // ---------------------------------------------------------------
    // Quote (§11)
    // ---------------------------------------------------------------

    @Nested
    class QuoteFares {

        @Test
        void combinesInventoryAvailabilityWithFareCalculatorBaseFares() {
            stubFlightOk();
            when(inventoryServiceClient.getCabins(10L)).thenReturn(Optional.of(List.of(
                    new InventoryCabinDetails(TravelClass.ECONOMY, 162, 87),
                    new InventoryCabinDetails(TravelClass.BUSINESS, 18, 4))));

            var quote = facade.quoteFares(10L);

            assertThat(quote.currency()).isEqualTo("GBP");
            // Only the cabins the flight sells - FIRST absent IS the answer (§7).
            assertThat(quote.cabins()).hasSize(2);
            var economy = quote.cabins().get(0);
            assertThat(economy.travelClass()).isEqualTo(TravelClass.ECONOMY);
            assertThat(economy.availableSeats()).isEqualTo(87);
            assertThat(economy.baseFares().get(FareType.SAVER)).isEqualByComparingTo("85.00");
            assertThat(economy.baseFares().get(FareType.FLEXI)).isEqualByComparingTo("100.00");
            assertThat(economy.baseFares().get(FareType.PREMIUM)).isEqualByComparingTo("125.00");
            assertThat(economy.fromFare()).isEqualByComparingTo("85.00");   // "Economy from 85"
            assertThat(quote.cabins().get(1).fromFare()).isEqualByComparingTo("297.50"); // "Business from 297.50"
        }

        @Test
        void flightWithoutInventoryQuotesAllCabinsWithUnknownAvailability() {
            stubFlightOk();
            when(inventoryServiceClient.getCabins(10L)).thenReturn(Optional.empty());

            var quote = facade.quoteFares(10L);

            assertThat(quote.cabins()).hasSize(TravelClass.values().length);
            assertThat(quote.cabins()).allSatisfy(cabin -> {
                assertThat(cabin.availableSeats()).isNull();
                assertThat(cabin.fromFare()).isNotNull();
            });
        }

        @Test
        void cancelledFlightIsNotQuotable() {
            when(flightServiceClient.getFlight(10L)).thenReturn(new FlightDetails(
                    10L, "AI131", "LHR", "DEL",
                    LocalDateTime.of(2030, 7, 16, 10, 0), LocalDateTime.of(2030, 7, 16, 19, 0),
                    null, null, FlightBookingStatus.CANCELLED));

            assertThatThrownBy(() -> facade.quoteFares(10L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cancelled");
        }
    }

    // ---------------------------------------------------------------
    // Cancel - inventory cleanup
    // ---------------------------------------------------------------

    @Test
    void cancelReleasesHoldsAndReservationsQuietlyThenPublishes() {
        BookingResponse cancelled = booking(BookingStatus.CANCELLED, "12A", "12B");
        when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CONFIRMED, "12A", "12B"));
        when(bookingService.cancelBooking(7L, "changed plans", 100)).thenReturn(cancelled);

        facade.cancelBooking(7L, "changed plans");

        verify(inventoryServiceClient).releaseHoldQuietly(eq(10L), eq("12A"), eq(7L), anyString());
        verify(inventoryServiceClient).cancelReservationQuietly(eq(10L), eq("12B"), eq(7L), anyString());
        verify(bookingEventProducer).publishBookingCancelled(eq(cancelled), any(), eq(100),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }
}
