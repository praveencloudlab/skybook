package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.client.InventoryServiceClient;
import com.skybook.praveen.bookingservice.client.RouteCalendarDay;
import com.skybook.praveen.bookingservice.domain.CancellationPolicy;
import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.dto.response.FareAlertResponse;
import com.skybook.praveen.bookingservice.dto.response.FareCalendarDayResponse;
import com.skybook.praveen.bookingservice.entity.FareAlert;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.repository.FareAlertRepository;
import com.skybook.praveen.bookingservice.service.BookingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Public shopping data (the fare calendar) and the fare watch behind it.
 * Both read the same deterministic FareCalculator checkout uses, so the
 * numbers here are the numbers a booking would charge.
 *
 * <p>The travel date is a Wednesday six-and-a-bit weeks out, computed from
 * today: far enough to sit in the 1.00 demand band and midweek enough to
 * dodge the Fri/Sun premium, whenever the suite happens to run.
 */
@ExtendWith(MockitoExtension.class)
class BookingFacadeFareWatchTest {

    private static final LocalDate NEUTRAL_TRAVEL_DATE = LocalDate.now().plusDays(45)
            .with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));

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

    @Captor
    private ArgumentCaptor<FareAlert> alertCaptor;

    private BookingFacade facade;

    @BeforeEach
    void setUp() {
        facade = new BookingFacade(flightServiceClient, inventoryServiceClient, bookingService,
                bookingEventProducer, new FareCalculator(), fareAlertRepository,
                new CancellationPolicy(new BigDecimal("30"), 72, 24, 2, 6));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, "n/a", List.of()));
    }

    private FareAlert alert(Long id, String owner, TravelClass travelClass, String lastNotifiedFare) {
        return FareAlert.builder()
                .id(id)
                .ownerSubject(owner)
                .originAirportCode("LHR")
                .destinationAirportCode("DXB")
                .travelDate(NEUTRAL_TRAVEL_DATE)
                .travelClass(travelClass)
                .lastNotifiedFare(new BigDecimal(lastNotifiedFare))
                .active(true)
                .build();
    }

    // ---------------------------------------------------------------
    // Fare calendar + the cheapest-fare formula behind it
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("The calendar prices each bookable day with checkout's own formula")
    class FareCalendar {

        @Test
        void quotesTheCheapestFareOfTheChosenCabinPerDay() {
            LocalDate secondDay = NEUTRAL_TRAVEL_DATE.plusDays(1);
            when(flightServiceClient.getRouteCalendar("LHR", "DXB", NEUTRAL_TRAVEL_DATE, secondDay))
                    .thenReturn(List.of(new RouteCalendarDay(NEUTRAL_TRAVEL_DATE, 3),
                            new RouteCalendarDay(secondDay, 1)));

            List<FareCalendarDayResponse> calendar = facade.fareCalendar(
                    "LHR", "DXB", NEUTRAL_TRAVEL_DATE, secondDay, TravelClass.ECONOMY);

            assertThat(calendar).hasSize(2);
            // Saver is the cheapest family: 100.00 base x 0.85.
            assertThat(calendar.get(0).date()).isEqualTo(NEUTRAL_TRAVEL_DATE);
            assertThat(calendar.get(0).flights()).isEqualTo(3);
            assertThat(calendar.get(0).minFare()).isEqualByComparingTo("85.00");
            assertThat(calendar.get(0).currency()).isEqualTo("GBP");
            assertThat(calendar.get(1).flights()).isEqualTo(1);
            assertThat(calendar.get(1).minFare()).isEqualByComparingTo("85.00");
        }

        @Test
        void theCabinDrivesThePriceOnTheSameDay() {
            when(flightServiceClient.getRouteCalendar("LHR", "DXB", NEUTRAL_TRAVEL_DATE, NEUTRAL_TRAVEL_DATE))
                    .thenReturn(List.of(new RouteCalendarDay(NEUTRAL_TRAVEL_DATE, 2)));

            List<FareCalendarDayResponse> calendar = facade.fareCalendar(
                    "LHR", "DXB", NEUTRAL_TRAVEL_DATE, NEUTRAL_TRAVEL_DATE, TravelClass.BUSINESS);

            // Business base 350.00 x Saver 0.85.
            assertThat(calendar.get(0).minFare()).isEqualByComparingTo("297.50");
        }

        @Test
        void aRouteWithNoBookableDaysQuotesNothing() {
            when(flightServiceClient.getRouteCalendar("LHR", "DXB", NEUTRAL_TRAVEL_DATE, NEUTRAL_TRAVEL_DATE))
                    .thenReturn(List.of());

            assertThat(facade.fareCalendar("LHR", "DXB", NEUTRAL_TRAVEL_DATE, NEUTRAL_TRAVEL_DATE,
                    TravelClass.ECONOMY)).isEmpty();
        }

        @Test
        void cheapestFareIsTheSameNumberTheCalendarPublishes() {
            assertThat(facade.cheapestFare(TravelClass.ECONOMY, NEUTRAL_TRAVEL_DATE))
                    .isEqualByComparingTo("85.00");
            assertThat(facade.cheapestFare(TravelClass.FIRST, NEUTRAL_TRAVEL_DATE))
                    .isEqualByComparingTo("595.00");
        }
    }

    // ---------------------------------------------------------------
    // Fare watch - the subject IS the mailbox alerts go to
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("A fare alert belongs to the subject that created it")
    class FareAlerts {

        @Test
        void watchingARouteBaselinesTodaysFareSoTheFirstMailIsARealChange() {
            authenticateAs("pax@example.com");
            when(fareAlertRepository.save(any(FareAlert.class))).thenAnswer(inv -> inv.getArgument(0));

            FareAlertResponse response = facade.createFareAlert(
                    "lhr", "dxb", NEUTRAL_TRAVEL_DATE, TravelClass.ECONOMY);

            verify(fareAlertRepository).save(alertCaptor.capture());
            FareAlert saved = alertCaptor.getValue();
            assertThat(saved.getOwnerSubject()).isEqualTo("pax@example.com");
            assertThat(saved.getOriginAirportCode()).isEqualTo("LHR");
            assertThat(saved.getDestinationAirportCode()).isEqualTo("DXB");
            assertThat(saved.isActive()).isTrue();
            // The baseline is today's fare, so the watcher is only mailed when
            // the price actually moves - never an echo of what they just saw.
            assertThat(saved.getLastNotifiedFare()).isEqualByComparingTo("85.00");
            assertThat(response.currentFare()).isEqualByComparingTo("85.00");
            assertThat(response.lastNotifiedFare()).isEqualByComparingTo("85.00");
            assertThat(response.currency()).isEqualTo("GBP");
            assertThat(response.travelClass()).isEqualTo(TravelClass.ECONOMY);
        }

        @Test
        void anAnonymousVisitorHasNoMailboxToAlert() {
            assertThatThrownBy(() -> facade.createFareAlert(
                    "LHR", "DXB", NEUTRAL_TRAVEL_DATE, TravelClass.ECONOMY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("signed-in owner");

            verify(fareAlertRepository, never()).save(any());
        }

        @Test
        void aDateThatHasAlreadyPassedCannotBeWatched() {
            authenticateAs("pax@example.com");

            assertThatThrownBy(() -> facade.createFareAlert(
                    "LHR", "DXB", LocalDate.now().minusDays(1), TravelClass.ECONOMY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("date in the past");

            verify(fareAlertRepository, never()).save(any());
        }

        @Test
        void myAlertsCarryTodaysFareAlongsideWhatWasLastNotified() {
            authenticateAs("pax@example.com");
            when(fareAlertRepository.findByOwnerSubjectAndActiveTrueOrderByTravelDateAsc("pax@example.com"))
                    .thenReturn(List.of(alert(1L, "pax@example.com", TravelClass.ECONOMY, "120.00"),
                            alert(2L, "pax@example.com", TravelClass.BUSINESS, "300.00")));

            List<FareAlertResponse> alerts = facade.myFareAlerts();

            assertThat(alerts).hasSize(2);
            // Watched at 120, now 85 - that gap is exactly what the sweep mails about.
            assertThat(alerts.get(0).currentFare()).isEqualByComparingTo("85.00");
            assertThat(alerts.get(0).lastNotifiedFare()).isEqualByComparingTo("120.00");
            assertThat(alerts.get(1).currentFare()).isEqualByComparingTo("297.50");
        }

        @Test
        void anAnonymousVisitorHasNoAlertsToList() {
            assertThat(facade.myFareAlerts()).isEmpty();

            verify(fareAlertRepository, never())
                    .findByOwnerSubjectAndActiveTrueOrderByTravelDateAsc(anyString());
        }

        @Test
        void deletingAnAlertDeactivatesItRatherThanErasingIt() {
            authenticateAs("pax@example.com");
            FareAlert existing = alert(1L, "pax@example.com", TravelClass.ECONOMY, "85.00");
            when(fareAlertRepository.findById(1L)).thenReturn(Optional.of(existing));

            facade.deleteFareAlert(1L);

            assertThat(existing.isActive()).isFalse();
            verify(fareAlertRepository).save(existing);
        }

        @Test
        void someoneElsesAlertCannotBeDeleted() {
            authenticateAs("intruder@example.com");
            when(fareAlertRepository.findById(1L))
                    .thenReturn(Optional.of(alert(1L, "pax@example.com", TravelClass.ECONOMY, "85.00")));

            assertThatThrownBy(() -> facade.deleteFareAlert(1L))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Not your alert");

            verify(fareAlertRepository, never()).save(any());
        }

        @Test
        void deletingAnAlertThatIsNotThereIsRejectedBeforeTheOwnerCheck() {
            when(fareAlertRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.deleteFareAlert(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No such fare alert");
        }
    }
}
