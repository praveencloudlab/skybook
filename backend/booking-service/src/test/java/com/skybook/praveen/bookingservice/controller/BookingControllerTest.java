package com.skybook.praveen.bookingservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skybook.praveen.bookingservice.dto.request.BookingContactRequest;
import com.skybook.praveen.bookingservice.dto.request.BookingSearchRequest;
import com.skybook.praveen.bookingservice.dto.request.CancelPassengersRequest;
import com.skybook.praveen.bookingservice.dto.request.ChangeSeatRequest;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.FareAlertRequest;
import com.skybook.praveen.bookingservice.dto.request.PassengerBookingDetail;
import com.skybook.praveen.bookingservice.dto.request.QuoteRequest;
import com.skybook.praveen.bookingservice.dto.request.RebookSegmentRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingContactResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;
import com.skybook.praveen.bookingservice.dto.response.CancellationPreviewResponse;
import com.skybook.praveen.bookingservice.dto.response.FareAlertResponse;
import com.skybook.praveen.bookingservice.dto.response.FareCalendarDayResponse;
import com.skybook.praveen.bookingservice.dto.response.QuoteResponse;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.exception.BookingNotFoundException;
import com.skybook.praveen.bookingservice.exception.GlobalExceptionHandler;
import com.skybook.praveen.bookingservice.exception.SeatUnavailableException;
import com.skybook.praveen.bookingservice.facade.BookingFacade;
import com.skybook.praveen.bookingservice.security.BookingAccessGuard;
import com.skybook.praveen.bookingservice.service.BookingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP layer only: routing, status codes, argument binding and - the part
 * that actually matters for security - that the ownership guard runs BEFORE
 * the service on every endpoint that takes a booking id from the URL. A
 * standalone setup keeps this a unit test; the guard's own logic and the
 * facade's orchestration are covered by their own suites.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingFacade bookingFacade;
    @Mock
    private BookingService bookingService;
    @Mock
    private BookingAccessGuard accessGuard;

    @Captor
    private ArgumentCaptor<BookingSearchRequest> searchCaptor;
    @Captor
    private ArgumentCaptor<CreateBookingRequest> createCaptor;

    private MockMvc mockMvc;

    // Boot's own default: dates as ISO-8601 strings, not epoch arrays - the
    // standalone builder does not apply the auto-configuration that sets it.
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @BeforeEach
    void setUp() {
        BookingController controller = new BookingController(bookingFacade, bookingService, accessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, "n/a", List.of()));
    }

    private static BookingResponse bookingResponse() {
        return new BookingResponse(
                77L, "SB1234", 500L, 9L, List.of(), BookingStatus.CONFIRMED,
                LocalDateTime.now(), new BigDecimal("142.00"), null, "auth|owner-1",
                List.of(), new BookingContactResponse("Praveen S", "praveen@example.com", null),
                null, List.of(), "system", "system", 1L, LocalDateTime.now(), LocalDateTime.now());
    }

    private static CreateBookingRequest createRequest() {
        return new CreateBookingRequest(
                500L, 9L, null, null,
                List.of(new PassengerBookingDetail(
                        "Mr", "Ann", null, "Blake",
                        LocalDate.now().minusYears(34), "FEMALE", "GBR", "P1234567",
                        LocalDate.now().plusYears(5), null, null,
                        TravelClass.ECONOMY, FareType.SAVER, "12A", null, null, 1, null)),
                new BookingContactRequest("Praveen S", "praveen@example.com", "+447700900000"),
                "window preferred");
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Nested
    @DisplayName("public shopping endpoints")
    class Shopping {

        @Test
        @DisplayName("creating a booking answers 201 and hands the whole request to the facade")
        void creatingABookingAnswers201() throws Exception {
            when(bookingFacade.createBooking(any(), any())).thenReturn(bookingResponse());

            mockMvc.perform(post("/api/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(createRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bookingReference").value("SB1234"))
                    .andExpect(jsonPath("$.id").value(77));

            verify(bookingFacade).createBooking(createCaptor.capture(), any());
            assertThat(createCaptor.getValue().flightId()).isEqualTo(9L);
            assertThat(createCaptor.getValue().passengers()).singleElement()
                    .satisfies(p -> assertThat(p.seatNumber()).isEqualTo("12A"));
        }

        @Test
        @DisplayName("a booking with no passengers is rejected at the edge, the facade is never called")
        void aBookingWithNoPassengersNeverReachesTheFacade() throws Exception {
            CreateBookingRequest empty = new CreateBookingRequest(
                    500L, 9L, null, null, List.of(),
                    new BookingContactRequest("Praveen S", "praveen@example.com", null), null);

            mockMvc.perform(post("/api/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(empty)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("At least one passenger is required")));

            verifyNoInteractions(bookingFacade);
        }

        @Test
        @DisplayName("a quote asks the facade for that one flight")
        void aQuoteAsksTheFacadeForThatFlight() throws Exception {
            when(bookingFacade.quoteFares(9L)).thenReturn(new QuoteResponse(9L, "GBP", List.of(
                    new QuoteResponse.CabinQuote(TravelClass.ECONOMY, 42,
                            Map.of(FareType.SAVER, new BigDecimal("85.00")), new BigDecimal("85.00")))));

            mockMvc.perform(post("/api/bookings/quote")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new QuoteRequest(9L))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.flightId").value(9))
                    .andExpect(jsonPath("$.cabins[0].availableSeats").value(42))
                    .andExpect(jsonPath("$.cabins[0].fromFare").value(85.00));
        }

        @Test
        @DisplayName("a quote without a flightId is a 400, not a null-flight lookup")
        void aQuoteWithoutAFlightIdIsA400() throws Exception {
            mockMvc.perform(post("/api/bookings/quote")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new QuoteRequest(null))))
                    .andExpect(status().isBadRequest());

            verify(bookingFacade, never()).quoteFares(anyLong());
        }

        @Test
        @DisplayName("the fare calendar binds its dates and cabin from the query string")
        void theFareCalendarBindsItsDatesAndCabin() throws Exception {
            LocalDate start = LocalDate.now().plusDays(10);
            LocalDate end = LocalDate.now().plusDays(20);
            when(bookingFacade.fareCalendar("LHR", "JFK", start, end, TravelClass.BUSINESS))
                    .thenReturn(List.of(new FareCalendarDayResponse(start, 4, new BigDecimal("350.00"), "GBP")));

            mockMvc.perform(get("/api/bookings/fare-calendar")
                            .param("originAirportCode", "LHR")
                            .param("destinationAirportCode", "JFK")
                            .param("startDate", start.toString())
                            .param("endDate", end.toString())
                            .param("travelClass", "BUSINESS"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].date").value(start.toString()))
                    .andExpect(jsonPath("$[0].flights").value(4))
                    .andExpect(jsonPath("$[0].minFare").value(350.00));
        }
    }

    @Nested
    @DisplayName("fare watches")
    class FareWatches {

        @Test
        @DisplayName("creating a watch forwards the route, date and cabin")
        void creatingAWatchForwardsTheRouteDateAndCabin() throws Exception {
            LocalDate travelDate = LocalDate.now().plusDays(40);
            when(bookingFacade.createFareAlert("LHR", "JFK", travelDate, TravelClass.ECONOMY))
                    .thenReturn(new FareAlertResponse(1L, "LHR", "JFK", travelDate, TravelClass.ECONOMY,
                            new BigDecimal("120.00"), null, "GBP"));

            mockMvc.perform(post("/api/bookings/fare-alerts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new FareAlertRequest("LHR", "JFK", travelDate, TravelClass.ECONOMY))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.currentFare").value(120.00));
        }

        @Test
        @DisplayName("a watch on a past date is rejected before it reaches the facade")
        void aWatchOnAPastDateIsRejected() throws Exception {
            mockMvc.perform(post("/api/bookings/fare-alerts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new FareAlertRequest("LHR", "JFK",
                                    LocalDate.now().minusDays(1), TravelClass.ECONOMY))))
                    .andExpect(status().isBadRequest());

            verify(bookingFacade, never()).createFareAlert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("listing my watches returns the facade's list unchanged")
        void listingMyWatchesReturnsTheFacadesList() throws Exception {
            when(bookingFacade.myFareAlerts()).thenReturn(List.of(
                    new FareAlertResponse(1L, "LHR", "JFK", LocalDate.now().plusDays(40),
                            TravelClass.ECONOMY, new BigDecimal("120.00"),
                            new BigDecimal("150.00"), "GBP")));

            mockMvc.perform(get("/api/bookings/fare-alerts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].originAirportCode").value("LHR"))
                    .andExpect(jsonPath("$[0].lastNotifiedFare").value(150.00));
        }

        @Test
        @DisplayName("deleting a watch answers 204 with no body")
        void deletingAWatchAnswers204() throws Exception {
            mockMvc.perform(delete("/api/bookings/fare-alerts/1"))
                    .andExpect(status().isNoContent());

            verify(bookingFacade).deleteFareAlert(1L);
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("/mine takes the subject from the token, never from the request")
        void mineTakesTheSubjectFromTheToken() throws Exception {
            authenticateAs("owner@example.com");
            when(bookingService.getBookingsForOwner("owner@example.com"))
                    .thenReturn(List.of(bookingResponse()));

            mockMvc.perform(get("/api/bookings/mine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].bookingReference").value("SB1234"));

            // No ownership check needed - there is no id the caller could tamper with.
            verifyNoInteractions(accessGuard);
        }

        @Test
        @DisplayName("/mine is routed as a literal, not parsed as a booking id")
        void mineIsRoutedAsALiteral() throws Exception {
            authenticateAs("owner@example.com");
            when(bookingService.getBookingsForOwner("owner@example.com")).thenReturn(List.of());

            mockMvc.perform(get("/api/bookings/mine")).andExpect(status().isOk());

            verify(bookingService, never()).getBookingById(anyLong());
        }

        @Test
        @DisplayName("fetching one booking checks ownership before it reads anything")
        void fetchingOneBookingChecksOwnershipFirst() throws Exception {
            when(bookingService.getBookingById(77L)).thenReturn(bookingResponse());

            mockMvc.perform(get("/api/bookings/77"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(77));

            InOrder order = inOrder(accessGuard, bookingService);
            order.verify(accessGuard).requireOwnerOfBooking(77L);
            order.verify(bookingService).getBookingById(77L);
        }

        @Test
        @DisplayName("a guard rejection stops the read and surfaces the domain status")
        void aGuardRejectionStopsTheRead() throws Exception {
            doThrow(new BookingNotFoundException(404L)).when(accessGuard).requireOwnerOfBooking(404L);

            mockMvc.perform(get("/api/bookings/404"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.path").value("/api/bookings/404"));

            verify(bookingService, never()).getBookingById(anyLong());
        }

        @Test
        @DisplayName("a PNR lookup is guarded by reference, not by id")
        void aPnrLookupIsGuardedByReference() throws Exception {
            when(bookingService.getBookingByReference("SB1234")).thenReturn(bookingResponse());

            mockMvc.perform(get("/api/bookings/reference/SB1234"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingReference").value("SB1234"));

            verify(accessGuard).requireOwnerOfBookingByReference("SB1234");
        }

        @Test
        @DisplayName("the back-office list-all is not per-owner guarded")
        void theBackOfficeListAllIsNotPerOwnerGuarded() throws Exception {
            when(bookingService.getAllBookings()).thenReturn(List.of(bookingResponse()));

            mockMvc.perform(get("/api/bookings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));

            verifyNoInteractions(accessGuard);
        }

        @Test
        @DisplayName("every search filter binds onto the criteria record")
        void everySearchFilterBindsOntoTheCriteria() throws Exception {
            LocalDate travelDate = LocalDate.now().plusDays(20);
            LocalDate bookingDate = LocalDate.now();
            when(bookingService.searchBookings(any())).thenReturn(List.of(bookingResponse()));

            mockMvc.perform(get("/api/bookings/search")
                            .param("bookingReference", "SB1234")
                            .param("flightId", "9")
                            .param("passengerName", "Blake")
                            .param("passportNumber", "P1234567")
                            .param("bookingStatus", "CONFIRMED")
                            .param("paymentStatus", "PAID")
                            .param("travelDate", travelDate.toString())
                            .param("bookingDate", bookingDate.toString())
                            .param("email", "praveen@example.com")
                            .param("phone", "+447700900000"))
                    .andExpect(status().isOk());

            verify(bookingService).searchBookings(searchCaptor.capture());
            BookingSearchRequest criteria = searchCaptor.getValue();
            assertThat(criteria.bookingReference()).isEqualTo("SB1234");
            assertThat(criteria.flightId()).isEqualTo(9L);
            assertThat(criteria.passengerName()).isEqualTo("Blake");
            assertThat(criteria.passportNumber()).isEqualTo("P1234567");
            assertThat(criteria.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(criteria.paymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(criteria.travelDate()).isEqualTo(travelDate);
            assertThat(criteria.bookingDate()).isEqualTo(bookingDate);
            assertThat(criteria.email()).isEqualTo("praveen@example.com");
            assertThat(criteria.phone()).isEqualTo("+447700900000");
        }

        @Test
        @DisplayName("a search with no filters is legal - every criterion is optional")
        void aSearchWithNoFiltersIsLegal() throws Exception {
            when(bookingService.searchBookings(any())).thenReturn(List.of());

            mockMvc.perform(get("/api/bookings/search")).andExpect(status().isOk());

            verify(bookingService).searchBookings(searchCaptor.capture());
            assertThat(searchCaptor.getValue().bookingReference()).isNull();
            assertThat(searchCaptor.getValue().flightId()).isNull();
            assertThat(searchCaptor.getValue().bookingStatus()).isNull();
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("the preview is guarded and returns the live tier")
        void thePreviewIsGuardedAndReturnsTheLiveTier() throws Exception {
            LocalDateTime departure = LocalDateTime.now().plusDays(30);
            when(bookingFacade.cancellationPreview(77L)).thenReturn(new CancellationPreviewResponse(
                    true, null, 100, departure, departure.minusHours(72), departure.minusHours(24),
                    departure.minusHours(2), false, new BigDecimal("142.00"), BigDecimal.ZERO,
                    BigDecimal.ZERO, new BigDecimal("142.00"), List.of()));

            mockMvc.perform(get("/api/bookings/77/cancellation-preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowed").value(true))
                    .andExpect(jsonPath("$.refundPercent").value(100))
                    .andExpect(jsonPath("$.refundAmount").value(142.00));

            verify(accessGuard).requireOwnerOfBooking(77L);
        }

        @Test
        @DisplayName("a cancel with a reason forwards it")
        void aCancelWithAReasonForwardsIt() throws Exception {
            when(bookingFacade.cancelBooking(77L, "change of plan")).thenReturn(bookingResponse());

            mockMvc.perform(patch("/api/bookings/77/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"change of plan\"}"))
                    .andExpect(status().isOk());

            verify(accessGuard).requireOwnerOfBooking(77L);
            verify(bookingFacade).cancelBooking(77L, "change of plan");
        }

        @Test
        @DisplayName("a cancel with no body at all still cancels, with a null reason")
        void aCancelWithNoBodyStillCancels() throws Exception {
            when(bookingFacade.cancelBooking(77L, null)).thenReturn(bookingResponse());

            mockMvc.perform(patch("/api/bookings/77/cancel"))
                    .andExpect(status().isOk());

            verify(bookingFacade).cancelBooking(77L, null);
        }

        @Test
        @DisplayName("cancelling passengers forwards exactly the selected rows")
        void cancellingPassengersForwardsTheSelectedRows() throws Exception {
            when(bookingFacade.cancelPassengers(77L, List.of(11L, 12L))).thenReturn(
                    new CancelPassengersResponse(bookingResponse(), new BigDecimal("71.00"),
                            false, List.of(11L, 12L)));

            mockMvc.perform(post("/api/bookings/77/passengers/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CancelPassengersRequest(List.of(11L, 12L)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refundAmount").value(71.00))
                    .andExpect(jsonPath("$.bookingCancelled").value(false))
                    .andExpect(jsonPath("$.cancelledRowIds.length()").value(2));

            verify(accessGuard).requireOwnerOfBooking(77L);
        }

        @Test
        @DisplayName("cancelling zero passengers is rejected before the guard even runs")
        void cancellingZeroPassengersIsRejected() throws Exception {
            mockMvc.perform(post("/api/bookings/77/passengers/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CancelPassengersRequest(List.of()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Select at least one passenger")));

            verifyNoInteractions(bookingFacade);
        }

        @Test
        @DisplayName("cancelling a segment binds the segment index off the path")
        void cancellingASegmentBindsTheSegmentIndex() throws Exception {
            when(bookingFacade.cancelSegment(77L, 1)).thenReturn(
                    new CancelPassengersResponse(bookingResponse(), new BigDecimal("71.00"),
                            false, List.of(12L)));

            mockMvc.perform(post("/api/bookings/77/segments/1/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cancelledRowIds[0]").value(12));

            verify(accessGuard).requireOwnerOfBooking(77L);
        }
    }

    @Nested
    @DisplayName("post-booking changes")
    class PostBookingChanges {

        @Test
        @DisplayName("a seat change is guarded and forwards the wanted seat")
        void aSeatChangeIsGuardedAndForwardsTheSeat() throws Exception {
            when(bookingFacade.changeSeat(77L, 11L, "20F")).thenReturn(bookingResponse());

            mockMvc.perform(post("/api/bookings/77/passengers/11/seat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new ChangeSeatRequest("20F"))))
                    .andExpect(status().isOk());

            InOrder order = inOrder(accessGuard, bookingFacade);
            order.verify(accessGuard).requireOwnerOfBooking(77L);
            order.verify(bookingFacade).changeSeat(77L, 11L, "20F");
        }

        @Test
        @DisplayName("a blank seat is rejected at the edge")
        void aBlankSeatIsRejectedAtTheEdge() throws Exception {
            mockMvc.perform(post("/api/bookings/77/passengers/11/seat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new ChangeSeatRequest(" "))))
                    .andExpect(status().isBadRequest());

            verify(bookingFacade, never()).changeSeat(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("a seat inventory refuses the change with a 409 carrying its reason")
        void aTakenSeatBecomesA409() throws Exception {
            when(bookingFacade.changeSeat(77L, 11L, "20F"))
                    .thenThrow(new SeatUnavailableException(9L, "20F", "already held"));

            mockMvc.perform(post("/api/bookings/77/passengers/11/seat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new ChangeSeatRequest("20F"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("already held")));
        }

        @Test
        @DisplayName("rebooking a segment forwards the replacement flight")
        void rebookingASegmentForwardsTheReplacementFlight() throws Exception {
            when(bookingFacade.rebookSegment(77L, 1, 30L)).thenReturn(bookingResponse());

            mockMvc.perform(post("/api/bookings/77/segments/1/rebook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new RebookSegmentRequest(30L))))
                    .andExpect(status().isOk());

            verify(accessGuard).requireOwnerOfBooking(77L);
            verify(bookingFacade).rebookSegment(77L, 1, 30L);
        }

        @Test
        @DisplayName("rebooking without a target flight is a 400")
        void rebookingWithoutATargetFlightIsA400() throws Exception {
            mockMvc.perform(post("/api/bookings/77/segments/1/rebook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new RebookSegmentRequest(null))))
                    .andExpect(status().isBadRequest());

            verify(bookingFacade, never()).rebookSegment(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                    anyLong());
        }
    }

    @Nested
    @DisplayName("back-office and per-passenger transitions")
    class Transitions {

        @Test
        @DisplayName("confirm is a back-office override - the facade, not the guard, owns it")
        void confirmIsABackOfficeOverride() throws Exception {
            when(bookingFacade.confirmBooking(77L)).thenReturn(bookingResponse());

            mockMvc.perform(patch("/api/bookings/77/confirm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));

            verifyNoInteractions(accessGuard);
        }

        @Test
        @DisplayName("complete is a back-office transition on the service")
        void completeIsABackOfficeTransition() throws Exception {
            when(bookingService.completeBooking(77L)).thenReturn(bookingResponse());

            mockMvc.perform(patch("/api/bookings/77/complete"))
                    .andExpect(status().isOk());

            verify(bookingService).completeBooking(77L);
            verifyNoInteractions(accessGuard);
        }

        @Test
        @DisplayName("check-in is per passenger row and guarded on the booking")
        void checkInIsPerPassengerRowAndGuarded() throws Exception {
            when(bookingService.checkInPassenger(77L, 11L)).thenReturn(bookingResponse());

            mockMvc.perform(patch("/api/bookings/77/passengers/11/check-in"))
                    .andExpect(status().isOk());

            InOrder order = inOrder(accessGuard, bookingService);
            order.verify(accessGuard).requireOwnerOfBooking(77L);
            order.verify(bookingService).checkInPassenger(77L, 11L);
        }

        @Test
        @DisplayName("boarding is per passenger row and guarded on the booking")
        void boardingIsPerPassengerRowAndGuarded() throws Exception {
            when(bookingService.boardPassenger(77L, 11L)).thenReturn(bookingResponse());

            mockMvc.perform(patch("/api/bookings/77/passengers/11/board"))
                    .andExpect(status().isOk());

            verify(accessGuard).requireOwnerOfBooking(77L);
            verify(bookingService).boardPassenger(77L, 11L);
        }

        @Test
        @DisplayName("an illegal transition surfaces as a 409, not a 500")
        void anIllegalTransitionSurfacesAsA409() throws Exception {
            when(bookingService.checkInPassenger(eq(77L), eq(11L)))
                    .thenThrow(new IllegalStateException("Booking must be CONFIRMED to check in"));

            mockMvc.perform(patch("/api/bookings/77/passengers/11/check-in"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Booking must be CONFIRMED to check in"));
        }
    }
}
