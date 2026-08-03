package com.skybook.praveen.bookingservice.scheduler;

import com.skybook.praveen.bookingservice.domain.FareCalculator;
import com.skybook.praveen.bookingservice.entity.FareAlert;
import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.repository.FareAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;

/**
 * Fare-watch sweep (passenger features): reprices every active alert with
 * the SAME deterministic FareCalculator checkout uses and emails the owner
 * when the fare moved since they were last told. Because demand pricing
 * rises toward departure, the mail also names the NEXT rise - computed by
 * running the calculator under a future-dated clock - so "book before
 * Friday" is a statement of fact, not marketing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FareAlertSweepJob {

    private final FareAlertRepository fareAlertRepository;
    private final FareCalculator fareCalculator;
    private final BookingEventProducer bookingEventProducer;

    @Scheduled(fixedDelayString = "${booking.fare-alerts.sweep-interval-ms:3600000}", initialDelay = 120000)
    @Transactional
    public void sweepFareAlerts() {

        int mailed = 0;
        for (FareAlert alert : fareAlertRepository.findByActiveTrue()) {

            if (alert.getTravelDate().isBefore(LocalDate.now())) {
                alert.setActive(false);
                continue;
            }

            BigDecimal current = cheapest(fareCalculator, alert.getTravelClass(), alert.getTravelDate());
            BigDecimal last = alert.getLastNotifiedFare();
            if (last == null) {
                alert.setLastNotifiedFare(current);
                continue;
            }
            if (last.compareTo(current) == 0) {
                continue;
            }

            String route = alert.getOriginAirportCode() + " → " + alert.getDestinationAirportCode();
            String direction = current.compareTo(last) > 0 ? "risen" : "dropped";
            StringBuilder message = new StringBuilder()
                    .append("The ").append(pretty(alert.getTravelClass())).append(" fare for ").append(route)
                    .append(" on ").append(alert.getTravelDate())
                    .append(" has ").append(direction).append(": GBP ").append(last)
                    .append(" is now GBP ").append(current).append(".");
            nextRise(alert, current).ifPresent(rise -> message
                    .append(" It rises again to GBP ").append(rise.fare())
                    .append(" on ").append(rise.date()).append(" - book before then."));
            message.append(" You are receiving this because you watch this fare on SkyBook;"
                    + " remove the watch from your profile page.");

            bookingEventProducer.publishFareAlert(alert.getOwnerSubject(),
                    "Fare update: " + route + " on " + alert.getTravelDate(), message.toString());
            alert.setLastNotifiedFare(current);
            mailed++;
        }

        if (mailed > 0) {
            log.info("FareAlertSweepJob mailed {} fare update(s)", mailed);
        }
    }

    private record Rise(LocalDate date, BigDecimal fare) {
    }

    /** First future day whose fare exceeds today's - the calculator run under that day's clock. */
    private java.util.Optional<Rise> nextRise(FareAlert alert, BigDecimal current) {
        LocalDate today = LocalDate.now();
        long horizon = Math.min(java.time.temporal.ChronoUnit.DAYS.between(today, alert.getTravelDate()), 120);
        for (long offset = 1; offset <= horizon; offset++) {
            LocalDate asOf = today.plusDays(offset);
            FareCalculator future = new FareCalculator(
                    Clock.fixed(asOf.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
            BigDecimal fare = cheapest(future, alert.getTravelClass(), alert.getTravelDate());
            if (fare.compareTo(current) > 0) {
                return java.util.Optional.of(new Rise(asOf, fare));
            }
        }
        return java.util.Optional.empty();
    }

    private static BigDecimal cheapest(FareCalculator calculator, TravelClass travelClass, LocalDate date) {
        return Arrays.stream(FareType.values())
                .map(fareType -> calculator.calculateFare(travelClass, fareType, date.atStartOfDay()))
                .min(BigDecimal::compareTo)
                .orElseThrow();
    }

    private static String pretty(TravelClass travelClass) {
        String s = travelClass.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
