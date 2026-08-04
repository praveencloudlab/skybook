package com.skybook.praveen.bookingservice.scheduler;

import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.entity.FareAlert;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.repository.FareAlertRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sweep mails real people, so the rules that decide WHETHER to mail
 * matter as much as the copy: an alert that mails on every run is spam, one
 * that never updates lastNotifiedFare mails the same move forever.
 *
 * The "next rise" line is produced by a REAL FareCalculator under a future
 * clock (the job builds its own), so those tests steer it by making today's
 * mocked fare either implausibly cheap (a rise is certain) or implausibly
 * expensive (no future date can beat it) instead of pinning exact pounds.
 */
@ExtendWith(MockitoExtension.class)
class FareAlertSweepJobTest {

    @Mock
    private FareAlertRepository fareAlertRepository;
    @Mock
    private FareCalculator fareCalculator;
    @Mock
    private BookingEventProducer bookingEventProducer;

    @InjectMocks
    private FareAlertSweepJob job;

    @Captor
    private ArgumentCaptor<String> messageCaptor;

    private static FareAlert alert(LocalDate travelDate, TravelClass travelClass, String lastNotified) {
        return FareAlert.builder()
                .id(1L)
                .ownerSubject("watcher@example.com")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .travelDate(travelDate)
                .travelClass(travelClass)
                .lastNotifiedFare(lastNotified == null ? null : new BigDecimal(lastNotified))
                .active(true)
                .build();
    }

    private void todaysFareIs(String fare) {
        when(fareCalculator.calculateFare(any(), any(), any())).thenReturn(new BigDecimal(fare));
    }

    private String captureMailedMessage() {
        verify(bookingEventProducer).publishFareAlert(eq("watcher@example.com"), anyString(),
                messageCaptor.capture());
        return messageCaptor.getValue();
    }

    @Nested
    @DisplayName("when the sweep must stay quiet")
    class StaysQuiet {

        @Test
        @DisplayName("nothing to watch means nothing is priced and nothing is sent")
        void nothingToWatchSendsNothing() {
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of());

            job.sweepFareAlerts();

            verifyNoInteractions(fareCalculator, bookingEventProducer);
        }

        @Test
        @DisplayName("an alert whose travel date has passed is retired, not repriced")
        void aPastAlertIsRetiredNotRepriced() {
            FareAlert expired = alert(LocalDate.now().minusDays(1), TravelClass.ECONOMY, "120.00");
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(expired));

            job.sweepFareAlerts();

            assertThat(expired.isActive()).isFalse();
            verifyNoInteractions(fareCalculator, bookingEventProducer);
        }

        @Test
        @DisplayName("the first sweep of a new alert only seeds the baseline - mailing it would be noise")
        void theFirstSweepOnlySeedsTheBaseline() {
            FareAlert fresh = alert(LocalDate.now().plusDays(40), TravelClass.ECONOMY, null);
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(fresh));
            todaysFareIs("120.00");

            job.sweepFareAlerts();

            assertThat(fresh.getLastNotifiedFare()).isEqualByComparingTo("120.00");
            verifyNoInteractions(bookingEventProducer);
        }

        @Test
        @DisplayName("an unmoved fare is not news - the owner hears nothing")
        void anUnmovedFareIsNotNews() {
            FareAlert unchanged = alert(LocalDate.now().plusDays(40), TravelClass.ECONOMY, "120.00");
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(unchanged));
            todaysFareIs("120.00");

            job.sweepFareAlerts();

            verify(bookingEventProducer, never()).publishFareAlert(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a fare equal only by scale (120.00 vs 120.0) is still unmoved")
        void aFareEqualOnlyByScaleIsStillUnmoved() {
            FareAlert unchanged = alert(LocalDate.now().plusDays(40), TravelClass.ECONOMY, "120.0");
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(unchanged));
            todaysFareIs("120.00");

            job.sweepFareAlerts();

            verify(bookingEventProducer, never()).publishFareAlert(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("when the fare has moved")
    class MailsTheOwner {

        @Test
        @DisplayName("a drop is worded as a drop and names both fares")
        void aDropIsWordedAsADrop() {
            FareAlert dropped = alert(LocalDate.now().plusDays(40), TravelClass.ECONOMY, "150.00");
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(dropped));
            todaysFareIs("120.00");

            job.sweepFareAlerts();

            assertThat(captureMailedMessage())
                    .contains("The Economy fare for LHR")
                    .contains("JFK")
                    .contains("has dropped: GBP 150.00 is now GBP 120.00.")
                    .contains("remove the watch from your profile page");
        }

        @Test
        @DisplayName("a rise is worded as a rise")
        void aRiseIsWordedAsARise() {
            FareAlert risen = alert(LocalDate.now().plusDays(40), TravelClass.ECONOMY, "100.00");
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(risen));
            todaysFareIs("130.00");

            job.sweepFareAlerts();

            assertThat(captureMailedMessage()).contains("has risen: GBP 100.00 is now GBP 130.00.");
        }

        @Test
        @DisplayName("the subject names the route and the travel date so the mail is scannable")
        void theSubjectNamesTheRouteAndDate() {
            LocalDate travelDate = LocalDate.now().plusDays(40);
            when(fareAlertRepository.findByActiveTrue())
                    .thenReturn(List.of(alert(travelDate, TravelClass.ECONOMY, "150.00")));
            todaysFareIs("120.00");

            job.sweepFareAlerts();

            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            verify(bookingEventProducer).publishFareAlert(eq("watcher@example.com"), subject.capture(),
                    anyString());
            assertThat(subject.getValue()).isEqualTo("Fare update: LHR → JFK on " + travelDate);
        }

        @Test
        @DisplayName("the baseline advances, so the same move is never mailed twice")
        void theBaselineAdvancesAfterMailing() {
            FareAlert moved = alert(LocalDate.now().plusDays(40), TravelClass.ECONOMY, "150.00");
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(moved));
            todaysFareIs("120.00");

            job.sweepFareAlerts();

            assertThat(moved.getLastNotifiedFare()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("the cabin name is humanised - PREMIUM_ECONOMY reads as Premium economy")
        void theCabinNameIsHumanised() {
            when(fareAlertRepository.findByActiveTrue()).thenReturn(
                    List.of(alert(LocalDate.now().plusDays(40), TravelClass.PREMIUM_ECONOMY, "250.00")));
            todaysFareIs("200.00");

            job.sweepFareAlerts();

            assertThat(captureMailedMessage()).contains("The Premium economy fare for");
        }

        @Test
        @DisplayName("several moved alerts each get their own mail")
        void severalMovedAlertsEachGetTheirOwnMail() {
            when(fareAlertRepository.findByActiveTrue()).thenReturn(List.of(
                    alert(LocalDate.now().plusDays(40), TravelClass.ECONOMY, "150.00"),
                    alert(LocalDate.now().plusDays(45), TravelClass.BUSINESS, "500.00")));
            todaysFareIs("120.00");

            job.sweepFareAlerts();

            verify(bookingEventProducer, times(2))
                    .publishFareAlert(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("the 'book before' line")
    class NextRise {

        @Test
        @DisplayName("a cheaper-than-real fare guarantees a future rise, dated and priced")
        void aFutureRiseIsDatedAndPriced() {
            // 1.00 is below any real cabin fare, so tomorrow's clock already beats it.
            when(fareAlertRepository.findByActiveTrue()).thenReturn(
                    List.of(alert(LocalDate.now().plusDays(10), TravelClass.ECONOMY, "0.50")));
            todaysFareIs("1.00");

            job.sweepFareAlerts();

            assertThat(captureMailedMessage())
                    .contains("It rises again to GBP")
                    .contains("on " + LocalDate.now().plusDays(1))
                    .contains("book before then");
        }

        @Test
        @DisplayName("when no future date prices higher the line is left off entirely")
        void noFutureRiseLeavesTheLineOff() {
            // Above every fare the calculator can produce for this cabin.
            when(fareAlertRepository.findByActiveTrue()).thenReturn(
                    List.of(alert(LocalDate.now().plusDays(4), TravelClass.ECONOMY, "100000.00")));
            todaysFareIs("99999.00");

            job.sweepFareAlerts();

            assertThat(captureMailedMessage())
                    .doesNotContain("It rises again")
                    .contains("has dropped: GBP 100000.00 is now GBP 99999.00.");
        }
    }
}
