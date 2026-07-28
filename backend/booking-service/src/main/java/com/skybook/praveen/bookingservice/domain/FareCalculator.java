package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Demand-shaped placeholder pricing (docs sections 9 and 12). Still not a real
 * revenue-management system, but no longer flat: the fare is
 *
 * <pre>  base(travel class) x fareType x demand(days to departure) x weekend</pre>
 *
 * so a seat bought the week of departure costs more than one bought three
 * months out, and Friday/Sunday departures carry a small premium - the shape
 * a fare calendar and a repeat visitor expect. Every factor is DETERMINISTIC
 * (no randomness, no per-flight state), which is what lets the quote page,
 * the fare calendar and checkout all agree to the cent: they all call this.
 *
 * <p>The clock is injectable so tests can pin "today"; production uses the
 * system clock via the no-arg constructor Spring picks.
 */
@Component
public class FareCalculator {

    private static final Map<TravelClass, BigDecimal> BASE_FARE_BY_CLASS = Map.of(
            TravelClass.ECONOMY, new BigDecimal("100.00"),
            TravelClass.PREMIUM_ECONOMY, new BigDecimal("180.00"),
            TravelClass.BUSINESS, new BigDecimal("350.00"),
            TravelClass.FIRST, new BigDecimal("700.00")
    );

    private static final Map<FareType, BigDecimal> FARE_TYPE_MULTIPLIER = Map.of(
            FareType.SAVER, new BigDecimal("0.85"),
            FareType.FLEXI, new BigDecimal("1.00"),
            FareType.PREMIUM, new BigDecimal("1.25")
    );

    /** Fri/Sun departures - the getaway premium. */
    private static final BigDecimal WEEKEND_MULTIPLIER = new BigDecimal("1.10");

    private final Clock clock;

    public FareCalculator() {
        this(Clock.systemDefaultZone());
    }

    public FareCalculator(Clock clock) {
        this.clock = clock;
    }

    public BigDecimal calculateFare(TravelClass travelClass, FareType fareType, LocalDateTime departureTime) {

        BigDecimal baseFare = BASE_FARE_BY_CLASS.get(travelClass);
        BigDecimal multiplier = FARE_TYPE_MULTIPLIER.get(fareType);

        return baseFare
                .multiply(multiplier)
                .multiply(demandMultiplier(departureTime.toLocalDate()))
                .multiply(weekendMultiplier(departureTime.toLocalDate()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * How far out the departure is drives the price: a late booking pays a
     * surge, an early one a discount, and the 31-60 day window is the 1.00
     * baseline the class-level base fares are quoted at.
     */
    private BigDecimal demandMultiplier(LocalDate departureDate) {
        long daysOut = ChronoUnit.DAYS.between(LocalDate.now(clock), departureDate);
        if (daysOut <= 3) {
            return new BigDecimal("1.60");
        }
        if (daysOut <= 7) {
            return new BigDecimal("1.40");
        }
        if (daysOut <= 14) {
            return new BigDecimal("1.20");
        }
        if (daysOut <= 30) {
            return new BigDecimal("1.10");
        }
        if (daysOut <= 60) {
            return BigDecimal.ONE;
        }
        if (daysOut <= 90) {
            return new BigDecimal("0.92");
        }
        return new BigDecimal("0.85");
    }

    private BigDecimal weekendMultiplier(LocalDate departureDate) {
        DayOfWeek day = departureDate.getDayOfWeek();
        return day == DayOfWeek.FRIDAY || day == DayOfWeek.SUNDAY
                ? WEEKEND_MULTIPLIER
                : BigDecimal.ONE;
    }
}
