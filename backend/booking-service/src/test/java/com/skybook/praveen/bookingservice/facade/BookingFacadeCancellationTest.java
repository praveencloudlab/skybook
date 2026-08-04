package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryServiceClient;
import com.skybook.praveen.bookingservice.domain.CancellationPolicy;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingPaymentResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingSegmentResponse;
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;
import com.skybook.praveen.bookingservice.dto.response.CancellationPreviewResponse;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.enums.SeatAssignmentMode;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.repository.FareAlertRepository;
import com.skybook.praveen.bookingservice.service.BookingService;
import com.skybook.praveen.common.time.AirportTimeZones;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The facade's cancellation surface: the time-tier guard every online cancel
 * path runs through, the refund breakdown the CANCELLED/PARTIALLY_CANCELLED
 * events carry, and the live preview the charges chart is drawn from.
 *
 * <p>Departures are anchored to the ORIGIN AIRPORT's wall clock rather than a
 * calendar date - that is the clock the policy is judged against, and a fixed
 * date would decide its own tier the moment it went past.
 */
@ExtendWith(MockitoExtension.class)
class BookingFacadeCancellationTest {

    private static final String ORIGIN = "LHR";
    private static final String RETURN_ORIGIN = "DXB";

    private static final long A_WEEK = 24 * 7;
    private static final long INSIDE_THE_HALF_TIER = 36;
    private static final long INSIDE_THE_ZERO_TIER = 10;
    private static final long INSIDE_THE_CLOSE_WINDOW = 1;

    @Mock
    private FlightServiceClient flightServiceClient;
    @Mock
    private InventoryServiceClient inventoryServiceClient;
    @Mock
    private BookingService bookingService;
    @Mock
    private BookingEventProducer bookingEventProducer;
    @Mock
    private FareAlertRepository fareAlertRepository;

    private BookingFacade facade;

    @BeforeEach
    void setUp() {
        facade = new BookingFacade(flightServiceClient, inventoryServiceClient, bookingService,
                bookingEventProducer, new FareCalculator(), fareAlertRepository,
                new CancellationPolicy(new BigDecimal("30"), 72, 24, 2));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, "n/a",
                        java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private FlightDetails outbound(long hoursFromNow) {
        LocalDateTime departure = AirportTimeZones.nowAt(ORIGIN).plusHours(hoursFromNow);
        return new FlightDetails(10L, "SB101", ORIGIN, RETURN_ORIGIN,
                departure, departure.plusHours(7), null, null, FlightBookingStatus.SCHEDULED);
    }

    private FlightDetails inbound(long hoursFromNow) {
        LocalDateTime departure = AirportTimeZones.nowAt(RETURN_ORIGIN).plusHours(hoursFromNow);
        return new FlightDetails(20L, "SB102", RETURN_ORIGIN, ORIGIN,
                departure, departure.plusHours(7), null, null, FlightBookingStatus.SCHEDULED);
    }

    private BookingPassengerResponse row(long id, int segmentIndex, Long flightId, FareType fareType,
                                         String seat, String fare, boolean cancelled) {
        return new BookingPassengerResponse(id, 100L + id, segmentIndex, flightId, "Pax" + id, "Test",
                "N000" + id, "Mr", "MALE", LocalDate.now().minusYears(35), "GBR",
                LocalDate.now().plusYears(5), TravelClass.ECONOMY, fareType, seat,
                new BigDecimal(fare), BigDecimal.ZERO, 0, BigDecimal.ZERO,
                SeatAssignmentMode.MANUAL, "GBP", new BigDecimal(fare),
                CheckInStatus.NOT_OPEN, cancelled, "ADULT");
    }

    private BookingPaymentResponse paid() {
        return new BookingPaymentResponse(PaymentStatus.PAID, new BigDecimal("200.00"), "GBP",
                "PAY-2026-K7M4Z9", LocalDateTime.now());
    }

    private BookingPaymentResponse pending() {
        return new BookingPaymentResponse(PaymentStatus.PENDING, new BigDecimal("200.00"), "GBP", null, null);
    }

    private List<BookingSegmentResponse> oneWay() {
        return List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"));
    }

    private List<BookingSegmentResponse> roundTrip() {
        return List.of(new BookingSegmentResponse(1L, 0, 10L, "UPCOMING"),
                new BookingSegmentResponse(2L, 1, 20L, "UPCOMING"));
    }

    private BookingResponse booking(BookingStatus status, BookingPaymentResponse payment,
                                    List<BookingSegmentResponse> segments,
                                    List<BookingPassengerResponse> passengers) {
        return new BookingResponse(7L, "SBCANC", 1L, 10L, segments, status, LocalDateTime.now(),
                new BigDecimal("200.00"), null, "pax@example.com", passengers, null, payment,
                List.of(), "system", "system", 0L, LocalDateTime.now(), LocalDateTime.now());
    }

    // ---------------------------------------------------------------
    // cancelBooking - tier guard + refund basis
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("cancelBooking applies the time tier before anything mutates")
    class WholeBookingCancel {

        private final List<BookingPassengerResponse> active = List.of(
                row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                row(2L, 0, 10L, FareType.SAVER, "12B", "100.00", false));

        private BookingResponse cancelled() {
            return booking(BookingStatus.CANCELLED, paid(), oneWay(), List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true),
                    row(2L, 0, 10L, FareType.SAVER, "12B", "100.00", true)));
        }

        @Test
        void aWeekOutRefundsTheFullTierAndShipsTheUpcomingFareLines() {
            BookingResponse cancelled = cancelled();
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), active));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(A_WEEK));
            when(bookingService.cancelBooking(7L, "changed plans", 100)).thenReturn(cancelled);

            facade.cancelBooking(7L, "changed plans");

            // Every active row's fare line rides the event so payment-service
            // refunds exactly what the tier quoted.
            verify(bookingEventProducer).publishBookingCancelled(eq(cancelled), any(), eq(100),
                    eq("FLEXI:100.00;SAVER:100.00"));
            verify(inventoryServiceClient).releaseHoldQuietly(10L, "12A", 7L, "booking cancelled");
            verify(inventoryServiceClient).cancelReservationQuietly(10L, "12B", 7L, "booking cancelled");
        }

        @Test
        void insideSeventyTwoHoursOnlyHalfTheFareRuleRefundSurvives() {
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), active));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_HALF_TIER));
            when(bookingService.cancelBooking(7L, "changed plans", 50)).thenReturn(cancelled());

            facade.cancelBooking(7L, "changed plans");

            verify(bookingService).cancelBooking(7L, "changed plans", 50);
            verify(bookingEventProducer).publishBookingCancelled(any(), any(), eq(50), anyString());
        }

        @Test
        void insideTwentyFourHoursTheFareIsForfeitedButTheSeatsStillGoBack() {
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), active));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_ZERO_TIER));
            when(bookingService.cancelBooking(7L, "changed plans", 0)).thenReturn(cancelled());

            facade.cancelBooking(7L, "changed plans");

            verify(bookingService).cancelBooking(7L, "changed plans", 0);
            verify(inventoryServiceClient).releaseHoldQuietly(10L, "12A", 7L, "booking cancelled");
        }

        @Test
        void insideTheCloseWindowNothingIsCancelledAtAll() {
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), active));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_CLOSE_WINDOW));

            assertThatThrownBy(() -> facade.cancelBooking(7L, "changed plans"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("airport desk");

            // The guard runs BEFORE anything mutates - no cancel, no seat
            // release, no event.
            verify(bookingService, never()).cancelBooking(anyLong(), anyString(), anyInt());
            verify(inventoryServiceClient, never()).releaseHoldQuietly(anyLong(), anyString(), anyLong(), anyString());
            verify(bookingEventProducer, never()).publishBookingCancelled(any(), any(), anyInt(), any());
        }

        @Test
        void aFullyDepartedJourneyCannotBeCancelledOnline() {
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), active));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(-3));

            assertThatThrownBy(() -> facade.cancelBooking(7L, "changed plans"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already departed");

            verify(bookingService, never()).cancelBooking(anyLong(), anyString(), anyInt());
        }

        @Test
        void anAdminDeskCancelIgnoresTheWindowAndRefundsOnFareRulesAlone() {
            authenticateAs("desk@skybook.com", "ROLE_ADMIN");
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), active));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_CLOSE_WINDOW));
            when(bookingService.cancelBooking(7L, "desk override", 100)).thenReturn(cancelled());

            facade.cancelBooking(7L, "desk override");

            // One hour to departure - closed to the passenger, open to the desk.
            verify(bookingService).cancelBooking(7L, "desk override", 100);
        }

        @Test
        void anUnpaidBookingCancelsFreelyEvenMinutesFromDeparture() {
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CREATED, pending(), oneWay(), active));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_CLOSE_WINDOW));
            when(bookingService.cancelBooking(7L, "never paid", 100))
                    .thenReturn(booking(BookingStatus.CANCELLED, pending(), oneWay(), List.of(
                            row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true),
                            row(2L, 0, 10L, FareType.SAVER, "12B", "100.00", true))));

            facade.cancelBooking(7L, "never paid");

            // Nothing was captured, so there is nothing to refund and no
            // breakdown to ship - only seats go back to the pool.
            verify(bookingEventProducer).publishBookingCancelled(any(), any(), eq(100), eq(null));
        }

        @Test
        void theSoonestUpcomingLegSetsTheTierForTheWholeBooking() {
            // Outbound inside the half tier, return a fortnight out: whichever
            // leg leaves first is the one the passenger is cancelling against.
            List<BookingPassengerResponse> journey = List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", false));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), roundTrip(), journey));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_HALF_TIER));
            when(flightServiceClient.getFlightAsService(20L)).thenReturn(inbound(A_WEEK * 2));
            when(bookingService.cancelBooking(7L, "changed plans", 50))
                    .thenReturn(booking(BookingStatus.CANCELLED, paid(), roundTrip(), journey));

            facade.cancelBooking(7L, "changed plans");

            verify(bookingService).cancelBooking(7L, "changed plans", 50);
            // Both legs are still to fly, so both carry refund value.
            verify(bookingEventProducer).publishBookingCancelled(any(), any(), eq(50),
                    eq("FLEXI:100.00;FLEXI:120.00"));
        }

        @Test
        void aFlownOutboundIsLeftOutOfTheRefundBasis() {
            List<BookingPassengerResponse> halfFlown = List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", false));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), roundTrip(), halfFlown));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(-3));
            when(flightServiceClient.getFlightAsService(20L)).thenReturn(inbound(A_WEEK));
            when(bookingService.cancelBooking(7L, "changed plans", 100))
                    .thenReturn(booking(BookingStatus.CANCELLED, paid(), roundTrip(), halfFlown));

            facade.cancelBooking(7L, "changed plans");

            // The used outbound carries no refund value; the tier is judged on
            // the return, which is the only leg still upcoming.
            verify(bookingEventProducer).publishBookingCancelled(any(), any(), eq(100), eq("FLEXI:120.00"));
        }
    }

    // ---------------------------------------------------------------
    // cancelPassengers / cancelSegment - partial cancellation
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("A cancellation that leaves the booking alive announces the money that moved")
    class PartialCancel {

        private final List<BookingPassengerResponse> bothActive = List.of(
                row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                row(2L, 0, 10L, FareType.FLEXI, "12B", "100.00", false));

        @Test
        void onlyTheCancelledPassengersSeatGoesBack() {
            BookingResponse survivor = booking(BookingStatus.PARTIALLY_CANCELLED, paid(), oneWay(), List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true),
                    row(2L, 0, 10L, FareType.FLEXI, "12B", "100.00", false)));
            CancelPassengersResponse result = new CancelPassengersResponse(
                    survivor, new BigDecimal("100.00"), false, List.of(1L));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), bothActive));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(A_WEEK));
            when(bookingService.cancelPassengers(7L, List.of(1L), 100)).thenReturn(result);

            facade.cancelPassengers(7L, List.of(1L));

            verify(inventoryServiceClient).releaseHoldQuietly(10L, "12A", 7L, "passenger cancelled");
            verify(inventoryServiceClient, never())
                    .releaseHoldQuietly(anyLong(), eq("12B"), anyLong(), anyString());
            verify(bookingEventProducer).publishBookingPartiallyCancelled(eq(survivor), any(), eq(100),
                    eq("FLEXI:100.00"), eq(List.of(1L)), eq("1 passenger seat(s)"),
                    eq(new BigDecimal("100.00")));
            verify(bookingEventProducer, never()).publishBookingCancelled(any(), any(), anyInt(), any());
        }

        @Test
        void cancellingTheLastPassengerAnnouncesAWholeBookingCancellation() {
            BookingResponse emptied = booking(BookingStatus.CANCELLED, paid(), oneWay(), List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true),
                    row(2L, 0, 10L, FareType.FLEXI, "12B", "100.00", true)));
            CancelPassengersResponse result = new CancelPassengersResponse(
                    emptied, new BigDecimal("200.00"), true, List.of(1L, 2L));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), oneWay(), bothActive));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(A_WEEK));
            when(bookingService.cancelPassengers(7L, List.of(1L, 2L), 100)).thenReturn(result);

            facade.cancelPassengers(7L, List.of(1L, 2L));

            verify(bookingEventProducer).publishBookingCancelled(eq(emptied), any(), eq(100),
                    eq("FLEXI:100.00;FLEXI:100.00"));
            verify(bookingEventProducer, never())
                    .publishBookingPartiallyCancelled(any(), any(), anyInt(), any(), any(), any(), any());
        }

        @Test
        void nothingCapturedMeansNoPartialCancellationEvent() {
            BookingResponse survivor = booking(BookingStatus.PARTIALLY_CANCELLED, pending(), oneWay(), List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true),
                    row(2L, 0, 10L, FareType.FLEXI, "12B", "100.00", false)));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CREATED, pending(), oneWay(), bothActive));
            when(bookingService.cancelPassengers(7L, List.of(1L), 100)).thenReturn(
                    new CancelPassengersResponse(survivor, BigDecimal.ZERO, false, List.of(1L)));

            facade.cancelPassengers(7L, List.of(1L));

            // No money was ever taken, so there is nothing for payment-service
            // to move - the booking-side numbers already tell the story.
            verify(inventoryServiceClient).releaseHoldQuietly(10L, "12A", 7L, "passenger cancelled");
            verify(bookingEventProducer, never())
                    .publishBookingPartiallyCancelled(any(), any(), anyInt(), any(), any(), any(), any());
        }

        @Test
        void droppingTheReturnReleasesOnlyThatLegsSeats() {
            List<BookingPassengerResponse> journey = List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", false));
            BookingResponse survivor = booking(BookingStatus.PARTIALLY_CANCELLED, paid(), roundTrip(), List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", true)));
            CancelPassengersResponse result = new CancelPassengersResponse(
                    survivor, new BigDecimal("120.00"), false, List.of(2L));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), roundTrip(), journey));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(A_WEEK));
            when(flightServiceClient.getFlightAsService(20L)).thenReturn(inbound(A_WEEK * 2));
            when(bookingService.cancelSegment(7L, 1, 100)).thenReturn(result);

            facade.cancelSegment(7L, 1);

            verify(inventoryServiceClient).releaseHoldQuietly(20L, "14C", 7L, "segment cancelled");
            verify(inventoryServiceClient, never())
                    .releaseHoldQuietly(anyLong(), eq("12A"), anyLong(), anyString());
            verify(bookingEventProducer).publishBookingPartiallyCancelled(eq(survivor), any(), eq(100),
                    eq("FLEXI:120.00"), eq(List.of(2L)), eq("the return journey"),
                    eq(new BigDecimal("120.00")));
        }

        @Test
        void droppingTheLastLiveSegmentAnnouncesAWholeBookingCancellation() {
            List<BookingPassengerResponse> journey = List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", false));
            BookingResponse emptied = booking(BookingStatus.CANCELLED, paid(), roundTrip(), List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", true)));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.PARTIALLY_CANCELLED, paid(), roundTrip(), journey));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(A_WEEK));
            when(flightServiceClient.getFlightAsService(20L)).thenReturn(inbound(A_WEEK * 2));
            when(bookingService.cancelSegment(7L, 1, 100)).thenReturn(new CancelPassengersResponse(
                    emptied, new BigDecimal("120.00"), true, List.of(2L)));

            facade.cancelSegment(7L, 1);

            // The outbound was already cancelled, so dropping the return
            // empties the booking - that is a full cancellation, not a partial.
            verify(bookingEventProducer).publishBookingCancelled(eq(emptied), any(), eq(100),
                    eq("FLEXI:120.00"));
            verify(bookingEventProducer, never())
                    .publishBookingPartiallyCancelled(any(), any(), anyInt(), any(), any(), any(), any());
        }

        @Test
        void aBookingWithNothingActiveLeftIsLeftToTheServiceToRefuse() {
            // Nothing in scope means no window to assess - the service owns
            // the "already cancelled" error, with the full context to word it.
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CANCELLED, paid(), oneWay(), List.of(
                            row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true))));
            when(bookingService.cancelPassengers(7L, List.of(1L), 100))
                    .thenThrow(new IllegalStateException(
                            "None of the selected passengers are on this booking, or they are already cancelled."));

            assertThatThrownBy(() -> facade.cancelPassengers(7L, List.of(1L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already cancelled");
        }

        @Test
        void theReturnsOwnDepartureDecidesTheTierWhenOnlyTheReturnIsDropped() {
            // The outbound leaves inside the zero-refund window, the return a
            // day and a half later. Dropping only the return is scoped to the
            // return's rows, so the return's clock sets the tier at 50 - the
            // sooner outbound never enters the assessment.
            List<BookingPassengerResponse> journey = List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", false));
            BookingResponse survivor = booking(BookingStatus.PARTIALLY_CANCELLED, paid(), roundTrip(), List.of(
                    row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                    row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", true)));
            when(bookingService.getBookingById(7L))
                    .thenReturn(booking(BookingStatus.CONFIRMED, paid(), roundTrip(), journey));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_ZERO_TIER));
            when(flightServiceClient.getFlightAsService(20L)).thenReturn(inbound(INSIDE_THE_HALF_TIER));
            when(bookingService.cancelSegment(7L, 1, 50)).thenReturn(new CancelPassengersResponse(
                    survivor, new BigDecimal("60.00"), false, List.of(2L)));

            facade.cancelSegment(7L, 1);

            verify(bookingService).cancelSegment(7L, 1, 50);
            verify(bookingEventProducer).publishBookingPartiallyCancelled(eq(survivor), any(), eq(50),
                    eq("FLEXI:120.00"), eq(List.of(2L)), eq("the return journey"),
                    eq(new BigDecimal("60.00")));
        }
    }

    // ---------------------------------------------------------------
    // cancellationPreview - reports, never throws
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("cancellationPreview quotes the money without moving any")
    class Preview {

        @Test
        void aWeekOutQuotesTheFullRefundWithAPerRowBreakdown() {
            FlightDetails flight = outbound(A_WEEK);
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CONFIRMED, paid(),
                    oneWay(), List.of(
                            row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                            row(2L, 0, 10L, FareType.FLEXI, "12B", "100.00", false))));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(flight);

            CancellationPreviewResponse preview = facade.cancellationPreview(7L);

            assertThat(preview.allowed()).isTrue();
            assertThat(preview.blockedReason()).isNull();
            assertThat(preview.refundPercent()).isEqualTo(100);
            assertThat(preview.unpaid()).isFalse();
            assertThat(preview.departureTime()).isEqualTo(flight.departureTime());
            assertThat(preview.fullRefundUntil()).isEqualTo(flight.departureTime().minusHours(72));
            assertThat(preview.halfRefundUntil()).isEqualTo(flight.departureTime().minusHours(24));
            assertThat(preview.cancelClosesAt()).isEqualTo(flight.departureTime().minusHours(2));
            assertThat(preview.totalPaid()).isEqualByComparingTo("200.00");
            assertThat(preview.refundAmount()).isEqualByComparingTo("200.00");
            assertThat(preview.fareRuleFee()).isEqualByComparingTo("0.00");
            assertThat(preview.timePenalty()).isEqualByComparingTo("0.00");
            assertThat(preview.lines()).hasSize(2);
            assertThat(preview.lines().get(0).passengerName()).isEqualTo("Pax1 Test");
            assertThat(preview.lines().get(0).fareType()).isEqualTo("FLEXI");
            assertThat(preview.lines().get(0).refund()).isEqualByComparingTo("100.00");
        }

        @Test
        void aSaverInsideTheHalfTierPaysTheFareRuleFeeAndTheTimePenalty() {
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CONFIRMED, paid(),
                    oneWay(), List.of(row(1L, 0, 10L, FareType.SAVER, "12A", "100.00", false))));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_HALF_TIER));

            CancellationPreviewResponse preview = facade.cancellationPreview(7L);

            // Fare rules bite first (Saver keeps 30), then the 50% tier halves
            // what survived: 100 - 30 = 70, of which 35 comes back.
            assertThat(preview.refundPercent()).isEqualTo(50);
            assertThat(preview.totalPaid()).isEqualByComparingTo("100.00");
            assertThat(preview.fareRuleFee()).isEqualByComparingTo("30.00");
            assertThat(preview.refundAmount()).isEqualByComparingTo("35.00");
            assertThat(preview.timePenalty()).isEqualByComparingTo("35.00");
        }

        @Test
        void anAlreadyCancelledBookingIsReportedNotThrown() {
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CANCELLED, paid(),
                    oneWay(), List.of(row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", true))));

            CancellationPreviewResponse preview = facade.cancellationPreview(7L);

            assertThat(preview.allowed()).isFalse();
            assertThat(preview.blockedReason()).contains("already cancelled");
            assertThat(preview.refundPercent()).isZero();
            assertThat(preview.departureTime()).isNull();
            assertThat(preview.lines()).isEmpty();
            assertThat(preview.refundAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        void aDepartedJourneyIsReportedNotThrown() {
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CONFIRMED, paid(),
                    oneWay(), List.of(row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false))));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(-3));

            CancellationPreviewResponse preview = facade.cancellationPreview(7L);

            assertThat(preview.allowed()).isFalse();
            assertThat(preview.blockedReason()).contains("already departed");
            assertThat(preview.lines()).isEmpty();
        }

        @Test
        void insideTheCloseWindowTheScheduleIsStillReportedAlongsideTheBlock() {
            FlightDetails flight = outbound(INSIDE_THE_CLOSE_WINDOW);
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CONFIRMED, paid(),
                    oneWay(), List.of(row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false))));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(flight);

            CancellationPreviewResponse preview = facade.cancellationPreview(7L);

            // Blocked, but the UI still gets the WHY and the whole schedule.
            assertThat(preview.allowed()).isFalse();
            assertThat(preview.blockedReason()).contains("Online cancellation closes");
            assertThat(preview.cancelClosesAt()).isEqualTo(flight.departureTime().minusHours(2));
            assertThat(preview.refundAmount()).isEqualByComparingTo("0.00");
            assertThat(preview.timePenalty()).isEqualByComparingTo("100.00");
            assertThat(preview.lines()).hasSize(1);
        }

        @Test
        void anUnpaidBookingPreviewsAsFreeToCancel() {
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CREATED, pending(),
                    oneWay(), List.of(row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false))));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(outbound(INSIDE_THE_CLOSE_WINDOW));

            CancellationPreviewResponse preview = facade.cancellationPreview(7L);

            // Nothing captured: the clock cannot withhold what was never taken.
            assertThat(preview.unpaid()).isTrue();
            assertThat(preview.allowed()).isTrue();
            assertThat(preview.refundPercent()).isZero();
            assertThat(preview.refundAmount()).isEqualByComparingTo("0.00");
            assertThat(preview.fareRuleFee()).isEqualByComparingTo("0.00");
            assertThat(preview.timePenalty()).isEqualByComparingTo("0.00");
            assertThat(preview.totalPaid()).isEqualByComparingTo("100.00");
        }

        @Test
        void theEarliestUpcomingLegGovernsTheTiers() {
            // Round trip whose return is weeks out but whose outbound is
            // inside the half tier - the sooner leg is what closes first.
            FlightDetails soonest = outbound(INSIDE_THE_HALF_TIER);
            when(bookingService.getBookingById(7L)).thenReturn(booking(BookingStatus.CONFIRMED, paid(),
                    roundTrip(), List.of(
                            row(1L, 0, 10L, FareType.FLEXI, "12A", "100.00", false),
                            row(2L, 1, 20L, FareType.FLEXI, "14C", "120.00", false))));
            when(flightServiceClient.getFlightAsService(10L)).thenReturn(soonest);
            when(flightServiceClient.getFlightAsService(20L)).thenReturn(inbound(A_WEEK * 3));

            CancellationPreviewResponse preview = facade.cancellationPreview(7L);

            assertThat(preview.departureTime()).isEqualTo(soonest.departureTime());
            assertThat(preview.refundPercent()).isEqualTo(50);
            assertThat(preview.totalPaid()).isEqualByComparingTo("220.00");
            assertThat(preview.refundAmount()).isEqualByComparingTo("110.00");
            assertThat(preview.lines()).hasSize(2);
        }
    }
}
