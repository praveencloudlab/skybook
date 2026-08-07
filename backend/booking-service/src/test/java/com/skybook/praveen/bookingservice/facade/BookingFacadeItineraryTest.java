package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryHoldDetails;
import com.skybook.praveen.bookingservice.client.InventoryReservationDetails;
import com.skybook.praveen.bookingservice.client.InventoryServiceClient;
import com.skybook.praveen.bookingservice.domain.CancellationPolicy;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.domain.SeatAssignmentResult;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.PassengerBookingDetail;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingSegmentResponse;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.FlightServiceUnavailableException;
import com.skybook.praveen.bookingservice.exception.SeatUnavailableException;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.repository.FareAlertRepository;
import com.skybook.praveen.bookingservice.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The facade's itinerary work beyond a plain one-way create: same-carrier
 * through-ticket connections, the back-office confirm, the Premium date
 * change, and the pre-check-in seat move. Departures are anchored to now so
 * no fixture can quietly become a past date and change what it tests.
 */
@ExtendWith(MockitoExtension.class)
class BookingFacadeItineraryTest {

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
    private FareAlertRepository fareAlertRepository;

    @Captor
    private ArgumentCaptor<List<BookingService.JourneyLeg>> journeyCaptor;
    @Captor
    private ArgumentCaptor<List<SeatAssignmentResult>> assignmentsCaptor;
    @Captor
    private ArgumentCaptor<Map<Long, String>> seatsCaptor;

    private BookingFacade facade;

    private static final LocalDateTime FIRST_DEPARTURE = LocalDateTime.now().plusDays(45).withNano(0);

    @BeforeEach
    void setUp() {
        facade = new BookingFacade(flightServiceClient, inventoryServiceClient, bookingService,
                bookingRepository, bookingEventProducer, new FareCalculator(), fareAlertRepository,
                new CancellationPolicy(new BigDecimal("30"), 72, 24, 2, 6));
    }

    private FlightDetails flight(Long id, String origin, String destination,
                                 LocalDateTime departure, LocalDateTime arrival) {
        return new FlightDetails(id, "SB" + id, origin, destination, departure, arrival,
                null, null, FlightBookingStatus.SCHEDULED);
    }

    private FlightDetails cancelledFlight(Long id) {
        return new FlightDetails(id, "SB" + id, "LHR", "DXB",
                FIRST_DEPARTURE, FIRST_DEPARTURE.plusHours(7), null, null, FlightBookingStatus.CANCELLED);
    }

    private PassengerBookingDetail detail(FareType fareType, String seat, List<String> connectionSeats) {
        return new PassengerBookingDetail("Mr", "Pax", null, "Test",
                LocalDate.now().minusYears(35), "MALE", "GBR",
                "P1234567", LocalDate.now().plusYears(5),
                "pax@example.com", "+441234567890",
                TravelClass.ECONOMY, fareType, seat, connectionSeats, null, null, null);
    }

    private BookingPassengerResponse row(long id, int segmentIndex, Long flightId, FareType fareType,
                                         String seat, String seatSurcharge, CheckInStatus checkInStatus,
                                         boolean cancelled) {
        return new BookingPassengerResponse(id, 100L, segmentIndex, flightId, "Pax", "Test", "P1234567",
                "Mr", "MALE", LocalDate.now().minusYears(35), "GBR", LocalDate.now().plusYears(5),
                TravelClass.ECONOMY, fareType, seat, new BigDecimal("100.00"),
                new BigDecimal(seatSurcharge), 0, BigDecimal.ZERO,
                SeatAssignmentMode.MANUAL, "GBP", new BigDecimal("100.00"),
                checkInStatus, cancelled, "ADULT");
    }

    private BookingResponse booking(BookingStatus status, List<BookingSegmentResponse> segments,
                                    List<BookingPassengerResponse> passengers) {
        return new BookingResponse(7L, "SBITIN", 1L, 10L, segments, status, LocalDateTime.now(),
                new BigDecimal("100.00"), null, "pax@example.com", passengers, null, null,
                List.of(), "system", "system", 0L, LocalDateTime.now(), LocalDateTime.now());
    }

    private InventoryHoldDetails hold(String seat, String mode, String listed, String charged) {
        return new InventoryHoldDetails(5L, seat, mode, new BigDecimal(listed), new BigDecimal(charged),
                "ACTIVE", LocalDateTime.now().plusMinutes(15));
    }

    // ---------------------------------------------------------------
    // Through-ticket connections - every leg validated before any row exists
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("A same-carrier connection books as one PNR of direction-0 legs")
    class ThroughTicketConnections {

        private final FlightDetails firstLeg = flight(10L, "LHR", "DXB",
                FIRST_DEPARTURE, FIRST_DEPARTURE.plusHours(7));
        private final FlightDetails onwardLeg = flight(11L, "DXB", "SYD",
                FIRST_DEPARTURE.plusHours(9), FIRST_DEPARTURE.plusHours(22));

        private final CreateBookingRequest request = new CreateBookingRequest(
                1L, 10L, null, List.of(11L),
                List.of(detail(FareType.FLEXI, "12A", List.of("7C"))), null, null);

        private BookingResponse draft() {
            return booking(BookingStatus.DRAFT,
                    List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                            new BookingSegmentResponse(2L, 1, 11L, "UPCOMING")),
                    List.of(row(1L, 0, 10L, FareType.FLEXI, null, "0.00", CheckInStatus.NOT_OPEN, false),
                            row(2L, 1, 11L, FareType.FLEXI, null, "0.00", CheckInStatus.NOT_OPEN, false)));
        }

        @Test
        void everyLegBecomesADirectionZeroSegmentAndTakesItsOwnSeatPick() {
            BookingResponse created = booking(BookingStatus.CREATED,
                    List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                            new BookingSegmentResponse(2L, 1, 11L, "UPCOMING")),
                    List.of(row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, false),
                            row(2L, 1, 11L, FareType.FLEXI, "7C", "0.00", CheckInStatus.NOT_OPEN, false)));
            when(flightServiceClient.getFlight(10L)).thenReturn(firstLeg);
            when(flightServiceClient.getFlight(11L)).thenReturn(onwardLeg);
            when(bookingService.createDraftBooking(eq(request), any(), any(), any(), any())).thenReturn(draft());
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("12A", "MANUAL", "12.00", "12.00")));
            when(inventoryServiceClient.holdSeat(11L, "7C", 7L, 2L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("7C", "MANUAL", "9.00", "9.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any())).thenReturn(created);

            facade.createBooking(request);

            verify(bookingService).createDraftBooking(eq(request), journeyCaptor.capture(), any(), any(), any());
            List<BookingService.JourneyLeg> journey = journeyCaptor.getValue();
            assertThat(journey).hasSize(2);
            assertThat(journey).extracting(BookingService.JourneyLeg::flightId).containsExactly(10L, 11L);
            // Both legs travel outbound; only the first starts the direction,
            // so baggage is billed once for the whole through-ticket.
            assertThat(journey).extracting(BookingService.JourneyLeg::direction).containsExactly(0, 0);
            assertThat(journey).extracting(BookingService.JourneyLeg::directionStart)
                    .containsExactly(true, false);
            assertThat(journey.get(1).originAirportCode()).isEqualTo("DXB");
            // The onward leg reads its own pick out of connectionSeatNumbers.
            verify(inventoryServiceClient).holdSeat(11L, "7C", 7L, 2L, TravelClass.ECONOMY);
            verify(bookingEventProducer).publishBookingCreated(created, List.of(firstLeg, onwardLeg));
        }

        @Test
        void aCancelledOnwardLegIsRejectedBeforeAnyRowExists() {
            when(flightServiceClient.getFlight(10L)).thenReturn(firstLeg);
            when(flightServiceClient.getFlight(11L)).thenReturn(cancelledFlight(11L));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cancelled connection flight");

            verify(bookingService, never()).createDraftBooking(any(), any(), any(), any(), any());
        }

        @Test
        void anOnwardLegLeavingBeforeThePreviousOneLandsIsRejected() {
            when(flightServiceClient.getFlight(10L)).thenReturn(firstLeg);
            when(flightServiceClient.getFlight(11L)).thenReturn(flight(11L, "DXB", "SYD",
                    firstLeg.arrivalTime().minusHours(1), firstLeg.arrivalTime().plusHours(12)));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("departs before the previous leg arrives");

            verify(bookingService, never()).createDraftBooking(any(), any(), any(), any(), any());
        }

        @Test
        void aCancelledFirstLegNeverReachesTheConnectionLookup() {
            when(flightServiceClient.getFlight(10L)).thenReturn(cancelledFlight(10L));

            assertThatThrownBy(() -> facade.createBooking(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot book a cancelled flight");

            verify(flightServiceClient, never()).getFlight(11L);
        }

        @Test
        void aSaverPassengerIsChargedTheSeatsListedPrice() {
            // Fare-family entitlement in reverse: Flexi and Premium have their
            // pick waived, Saver pays what inventory listed.
            CreateBookingRequest saverRequest = new CreateBookingRequest(
                    1L, 10L, null, null, List.of(detail(FareType.SAVER, "12A", null)), null, null);
            List<BookingSegmentResponse> oneWay = List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"));
            BookingResponse saverDraft = booking(BookingStatus.DRAFT, oneWay,
                    List.of(row(1L, 0, 10L, FareType.SAVER, null, "0.00", CheckInStatus.NOT_OPEN, false)));
            BookingResponse saverCreated = booking(BookingStatus.CREATED, oneWay,
                    List.of(row(1L, 0, 10L, FareType.SAVER, "12A", "12.00", CheckInStatus.NOT_OPEN, false)));
            when(flightServiceClient.getFlight(10L)).thenReturn(firstLeg);
            when(bookingService.createDraftBooking(eq(saverRequest), any(), any(), any(), any())).thenReturn(saverDraft);
            when(inventoryServiceClient.holdSeat(10L, "12A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("12A", "MANUAL", "12.00", "12.00")));
            when(bookingService.finalizeSeatAssignments(eq(7L), any())).thenReturn(saverCreated);

            facade.createBooking(saverRequest);

            verify(bookingService).finalizeSeatAssignments(eq(7L), assignmentsCaptor.capture());
            assertThat(assignmentsCaptor.getValue().get(0).listedSurcharge()).isEqualByComparingTo("12.00");
            assertThat(assignmentsCaptor.getValue().get(0).chargedSurcharge()).isEqualByComparingTo("12.00");
        }
    }

    // ---------------------------------------------------------------
    // Back-office confirm (the manual override)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("The back-office confirm converts holds to reservations")
    class BackOfficeConfirm {

        @Test
        void reservesEverySeatedRowAndSkipsTheSeatlessOnes() {
            BookingResponse confirmed = booking(BookingStatus.CONFIRMED,
                    List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING")),
                    List.of(row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, false),
                            row(2L, 0, 10L, FareType.FLEXI, null, "0.00", CheckInStatus.NOT_OPEN, false)));
            when(bookingService.confirmBooking(7L)).thenReturn(confirmed);
            when(inventoryServiceClient.reserveSeat(10L, "12A", 7L, 1L))
                    .thenReturn(Optional.of(new InventoryReservationDetails(9L, "12A", "RESERVED")));

            BookingResponse result = facade.confirmBooking(7L);

            assertThat(result.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(inventoryServiceClient).reserveSeat(10L, "12A", 7L, 1L);
            // A row without a seat (no inventory on the flight) has nothing to
            // convert - it must not become a null-seat reservation call.
            verify(inventoryServiceClient, never()).reserveSeat(anyLong(), isNull(), anyLong(), anyLong());
            verify(bookingEventProducer).publishBookingConfirmed(eq(confirmed), any());
        }

        @Test
        void aBookingWithoutSegmentsIsAnnouncedWithoutFlightContext() {
            BookingResponse confirmed = booking(BookingStatus.CONFIRMED, List.of(),
                    List.of(row(1L, 0, 10L, FareType.FLEXI, "   ", "0.00", CheckInStatus.NOT_OPEN, false)));
            when(bookingService.confirmBooking(7L)).thenReturn(confirmed);

            facade.confirmBooking(7L);

            // A blank seat is no seat - there is nothing to convert.
            verify(inventoryServiceClient, never()).reserveSeat(anyLong(), anyString(), anyLong(), anyLong());
            // Enrichment is best-effort: no segments, no lookups, and the
            // email simply goes out without route details.
            verify(flightServiceClient, never()).getFlightAsService(anyLong());
            verify(bookingEventProducer).publishBookingConfirmed(confirmed, List.of());
        }
    }

    // ---------------------------------------------------------------
    // Premium date change (ROUND_TRIP_MODULE.md §11)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("rebookSegment moves one leg and re-seats it on the new flight")
    class RebookSegment {

        private final FlightDetails outbound = flight(10L, "LHR", "DXB",
                FIRST_DEPARTURE, FIRST_DEPARTURE.plusHours(7));
        private final FlightDetails replacement = flight(30L, "DXB", "LHR",
                FIRST_DEPARTURE.plusDays(10), FIRST_DEPARTURE.plusDays(10).plusHours(7));

        private final List<BookingSegmentResponse> segments = List.of(
                new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                new BookingSegmentResponse(2L, 1, 20L, "UPCOMING"));

        private BookingResponse current() {
            return booking(BookingStatus.CONFIRMED, segments, List.of(
                    row(1L, 0, 10L, FareType.PREMIUM, "12A", "0.00", CheckInStatus.NOT_OPEN, false),
                    row(2L, 1, 20L, FareType.PREMIUM, "14C", "0.00", CheckInStatus.NOT_OPEN, false)));
        }

        /** After the exchange: the old return row is cancelled, a seatless replacement row exists. */
        private BookingResponse rebooked() {
            return booking(BookingStatus.CONFIRMED,
                    List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                            new BookingSegmentResponse(2L, 1, 30L, "UPCOMING")),
                    List.of(row(1L, 0, 10L, FareType.PREMIUM, "12A", "0.00", CheckInStatus.NOT_OPEN, false),
                            row(2L, 1, 20L, FareType.PREMIUM, "14C", "0.00", CheckInStatus.NOT_OPEN, true),
                            row(3L, 1, 30L, FareType.PREMIUM, null, "0.00", CheckInStatus.NOT_OPEN, false)));
        }

        @Test
        void releasesTheOldSeatAndAutoSeatsTheReplacementRow() {
            BookingResponse rebooked = rebooked();
            BookingResponse seated = booking(BookingStatus.CONFIRMED,
                    List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                            new BookingSegmentResponse(2L, 1, 30L, "UPCOMING")),
                    List.of(row(1L, 0, 10L, FareType.PREMIUM, "12A", "0.00", CheckInStatus.NOT_OPEN, false),
                            row(3L, 1, 30L, FareType.PREMIUM, "21F", "0.00", CheckInStatus.NOT_OPEN, false)));
            when(flightServiceClient.getFlight(30L)).thenReturn(replacement);
            when(bookingService.getBookingById(7L)).thenReturn(current());
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound);
            when(flightServiceClient.getFlightAsService(30L)).thenReturn(replacement);
            when(bookingService.rebookSegment(7L, 1, 30L, replacement.departureTime())).thenReturn(rebooked);
            when(inventoryServiceClient.autoHoldSeat(30L, 7L, 3L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("21F", "AUTO", "0.00", "0.00")));
            when(inventoryServiceClient.reserveSeat(30L, "21F", 7L, 3L))
                    .thenReturn(Optional.of(new InventoryReservationDetails(9L, "21F", "RESERVED")));
            when(bookingService.applySeatNumbers(eq(7L), any())).thenReturn(seated);

            BookingResponse result = facade.rebookSegment(7L, 1, 30L);

            assertThat(result).isSameAs(seated);
            // The exchanged row's seat goes back only AFTER the exchange
            // committed; the fresh row is held and reserved on the new flight.
            verify(inventoryServiceClient).releaseHoldQuietly(20L, "14C", 7L, "segment rebooked");
            verify(inventoryServiceClient).cancelReservationQuietly(20L, "14C", 7L, "segment rebooked");
            verify(bookingService).applySeatNumbers(eq(7L), seatsCaptor.capture());
            assertThat(seatsCaptor.getValue()).containsExactly(Map.entry(3L, "21F"));
            verify(bookingEventProducer).publishBookingConfirmed(eq(seated), any());
        }

        @Test
        void aFailedAutoSeatLeavesTheRowSeatlessInsteadOfFailingTheChange() {
            BookingResponse rebooked = rebooked();
            when(flightServiceClient.getFlight(30L)).thenReturn(replacement);
            when(bookingService.getBookingById(7L)).thenReturn(current());
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound);
            when(flightServiceClient.getFlightAsService(30L)).thenReturn(replacement);
            when(bookingService.rebookSegment(7L, 1, 30L, replacement.departureTime())).thenReturn(rebooked);
            when(inventoryServiceClient.autoHoldSeat(30L, 7L, 3L, TravelClass.ECONOMY))
                    .thenThrow(new SeatUnavailableException(30L, "(auto)", "cabin full"));

            BookingResponse result = facade.rebookSegment(7L, 1, 30L);

            // Best effort by design: the traveller picks a seat at check-in
            // rather than losing a date change that already committed.
            assertThat(result).isSameAs(rebooked);
            verify(bookingService, never()).applySeatNumbers(anyLong(), any());
            verify(bookingEventProducer).publishBookingConfirmed(eq(rebooked), any());
        }

        @Test
        void anUnreachableOtherLegDoesNotBlockTheDateChange() {
            // The clash check is advisory - it must not turn a flight-service
            // hiccup into a failed date change.
            BookingResponse rebooked = rebooked();
            when(flightServiceClient.getFlight(30L)).thenReturn(replacement);
            when(bookingService.getBookingById(7L)).thenReturn(current());
            when(flightServiceClient.getFlightAsService(10L))
                    .thenThrow(new FlightServiceUnavailableException(10L, new RuntimeException("connect timed out")));
            when(flightServiceClient.getFlightAsService(30L)).thenReturn(replacement);
            when(bookingService.rebookSegment(7L, 1, 30L, replacement.departureTime())).thenReturn(rebooked);
            when(inventoryServiceClient.autoHoldSeat(30L, 7L, 3L, TravelClass.ECONOMY))
                    .thenReturn(Optional.empty());

            BookingResponse result = facade.rebookSegment(7L, 1, 30L);

            assertThat(result).isSameAs(rebooked);
            // No inventory on the new flight either - the row simply stays
            // seatless rather than the change failing.
            verify(bookingService, never()).applySeatNumbers(anyLong(), any());
            verify(bookingEventProducer).publishBookingConfirmed(eq(rebooked), any());
        }

        @Test
        void aReplacementRowThatAlreadyHasASeatIsLeftAlone() {
            BookingResponse alreadySeated = booking(BookingStatus.CONFIRMED,
                    List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                            new BookingSegmentResponse(2L, 1, 30L, "UPCOMING")),
                    List.of(row(1L, 0, 10L, FareType.PREMIUM, "12A", "0.00", CheckInStatus.NOT_OPEN, false),
                            row(3L, 1, 30L, FareType.PREMIUM, "21F", "0.00", CheckInStatus.NOT_OPEN, false)));
            when(flightServiceClient.getFlight(30L)).thenReturn(replacement);
            when(bookingService.getBookingById(7L)).thenReturn(current());
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound);
            when(flightServiceClient.getFlightAsService(30L)).thenReturn(replacement);
            when(bookingService.rebookSegment(7L, 1, 30L, replacement.departureTime()))
                    .thenReturn(alreadySeated);

            facade.rebookSegment(7L, 1, 30L);

            verify(inventoryServiceClient, never()).autoHoldSeat(anyLong(), anyLong(), anyLong(), any());
            verify(bookingService, never()).applySeatNumbers(anyLong(), any());
        }

        @Test
        void aNewDateThatClashesWithTheOtherLegIsRejected() {
            when(flightServiceClient.getFlight(30L)).thenReturn(flight(30L, "DXB", "LHR",
                    outbound.arrivalTime().minusHours(2), outbound.arrivalTime().plusHours(5)));
            when(bookingService.getBookingById(7L)).thenReturn(current());
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound);

            assertThatThrownBy(() -> facade.rebookSegment(7L, 1, 30L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("clashes with the other leg");

            verify(bookingService, never()).rebookSegment(anyLong(), anyInt(), anyLong(), any());
        }

        @Test
        void aCancelledTargetFlightIsRejectedBeforeTheBookingIsEvenRead() {
            when(flightServiceClient.getFlight(30L)).thenReturn(cancelledFlight(30L));

            assertThatThrownBy(() -> facade.rebookSegment(7L, 1, 30L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cancelled flight");

            verify(bookingService, never()).getBookingById(anyLong());
        }
    }

    // ---------------------------------------------------------------
    // Pre-check-in seat change (§9 entitlement ceiling)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("changeSeat secures the new seat before releasing the old one")
    class ChangeSeat {

        private final List<BookingSegmentResponse> segments =
                List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"));

        private BookingResponse confirmedWith(BookingPassengerResponse... rows) {
            return booking(BookingStatus.CONFIRMED, segments, List.of(rows));
        }

        @Test
        void holdsTheNewSeatReservesItThenReturnsTheOldOneToThePool() {
            BookingResponse updated = confirmedWith(
                    row(1L, 0, 10L, FareType.FLEXI, "14C", "0.00", CheckInStatus.NOT_OPEN, false));
            when(bookingService.getBookingById(7L)).thenReturn(confirmedWith(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, false)));
            when(inventoryServiceClient.holdSeat(10L, "14C", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("14C", "MANUAL", "15.00", "15.00")));
            when(inventoryServiceClient.reserveSeat(10L, "14C", 7L, 1L))
                    .thenReturn(Optional.of(new InventoryReservationDetails(9L, "14C", "RESERVED")));
            when(bookingService.updateSeatNumber(7L, 1L, "14C")).thenReturn(updated);

            BookingResponse result = facade.changeSeat(7L, 1L, "14c");

            assertThat(result).isSameAs(updated);
            // Hold-new-first ordering: a failure can never leave the traveller
            // seatless, so the release only happens after the reservation.
            verify(inventoryServiceClient).reserveSeat(10L, "14C", 7L, 1L);
            verify(inventoryServiceClient).releaseHoldQuietly(10L, "12A", 7L, "seat changed");
            verify(inventoryServiceClient).cancelReservationQuietly(10L, "12A", 7L, "seat changed");
        }

        @Test
        void askingForTheSeatAlreadyHeldTouchesNothing() {
            BookingResponse current = confirmedWith(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, false));
            when(bookingService.getBookingById(7L)).thenReturn(current);

            BookingResponse result = facade.changeSeat(7L, 1L, "12a");

            assertThat(result).isSameAs(current);
            verify(inventoryServiceClient, never()).holdSeat(anyLong(), anyString(), anyLong(), anyLong(), any());
            verify(bookingService, never()).updateSeatNumber(anyLong(), anyLong(), anyString());
        }

        @Test
        void aSaverCannotMoveAboveTheSurchargeTheyPaid() {
            when(bookingService.getBookingById(7L)).thenReturn(confirmedWith(
                    row(1L, 0, 10L, FareType.SAVER, "12A", "12.00", CheckInStatus.NOT_OPEN, false)));
            when(inventoryServiceClient.holdSeat(10L, "1A", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("1A", "MANUAL", "25.00", "25.00")));

            assertThatThrownBy(() -> facade.changeSeat(7L, 1L, "1A"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("above the 12.00 your Saver fare paid");

            // The speculative hold is handed straight back - a rejected move
            // must not park inventory on a seat nobody bought.
            verify(inventoryServiceClient).releaseHoldQuietly(10L, "1A", 7L, "seat change above fare ceiling");
            verify(inventoryServiceClient, never()).reserveSeat(anyLong(), anyString(), anyLong(), anyLong());
            verify(bookingService, never()).updateSeatNumber(anyLong(), anyLong(), anyString());
        }

        @Test
        void aSaverMayMoveWithinTheSurchargeTheyPaid() {
            BookingResponse updated = confirmedWith(
                    row(1L, 0, 10L, FareType.SAVER, "14C", "12.00", CheckInStatus.NOT_OPEN, false));
            when(bookingService.getBookingById(7L)).thenReturn(confirmedWith(
                    row(1L, 0, 10L, FareType.SAVER, "12A", "12.00", CheckInStatus.NOT_OPEN, false)));
            when(inventoryServiceClient.holdSeat(10L, "14C", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.of(hold("14C", "MANUAL", "12.00", "12.00")));
            when(inventoryServiceClient.reserveSeat(10L, "14C", 7L, 1L))
                    .thenReturn(Optional.of(new InventoryReservationDetails(9L, "14C", "RESERVED")));
            when(bookingService.updateSeatNumber(7L, 1L, "14C")).thenReturn(updated);

            // Equal to the ceiling is inside it - stored money never moves.
            assertThat(facade.changeSeat(7L, 1L, "14C")).isSameAs(updated);
        }

        @Test
        void aFlightWithoutSeatInventoryHasNoSeatToMoveTo() {
            when(bookingService.getBookingById(7L)).thenReturn(confirmedWith(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, false)));
            when(inventoryServiceClient.holdSeat(10L, "14C", 7L, 1L, TravelClass.ECONOMY))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.changeSeat(7L, 1L, "14C"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no seat inventory");
        }

        @Test
        void seatsOnlyMoveBetweenPaymentAndCheckIn() {
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CREATED, segments,
                    List.of(row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, false))));

            assertThatThrownBy(() -> facade.changeSeat(7L, 1L, "14C"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("after payment and before check-in");
        }

        @Test
        void aCheckedInPassengerChangesSeatsAtCheckInInstead() {
            when(bookingService.getBookingById(7L)).thenReturn(confirmedWith(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.CHECKED_IN, false)));

            assertThatThrownBy(() -> facade.changeSeat(7L, 1L, "14C"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Already checked in");
        }

        @Test
        void aCancelledPassengerHasNoSeatToChange() {
            when(bookingService.getBookingById(7L)).thenReturn(confirmedWith(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, true)));

            assertThatThrownBy(() -> facade.changeSeat(7L, 1L, "14C"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cancelled off the booking");
        }

        @Test
        void aPassengerFromAnotherBookingIsRejected() {
            when(bookingService.getBookingById(7L)).thenReturn(confirmedWith(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "0.00", CheckInStatus.NOT_OPEN, false)));

            assertThatThrownBy(() -> facade.changeSeat(7L, 99L, "14C"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No such passenger on this booking");
        }
    }
}
