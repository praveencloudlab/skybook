package com.skybook.praveen.flightservice.entity;

import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * A recurring flight template, e.g. "BA178 flies LHR -> JFK every Mon/Wed/Fri
 * at 10:15 between 2026-07-01 and 2026-09-30". Concrete {@link Flight}
 * instances are generated from this template on a rolling basis.
 */
@Entity
@Table(name = "flight_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightSchedule extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Immutable, system-assigned identifier for this schedule, distinct from
    // the (re-used) airline flight number, e.g. "SCH-LHR-JFK-000001".
    // Assigned once at creation time and never changed afterwards.
    @Column(nullable = false, unique = true, updatable = false, length = 30)
    private String scheduleCode;

    @Column(nullable = false, length = 10)
    private String flightNumber;

    @Column(nullable = false, length = 5)
    private String airlineCode;

    @Column(nullable = false, length = 3)
    private String originAirportCode;

    @Column(nullable = false, length = 3)
    private String destinationAirportCode;

    /** Local time of day the flight departs on each operating day. */
    @Column(nullable = false)
    private LocalTime departureTime;

    /** Local time of day the flight arrives. If before departureTime, treated as next-day arrival. */
    @Column(nullable = false)
    private LocalTime arrivalTime;

    @ElementCollection(targetClass = DayOfWeek.class, fetch = FetchType.EAGER)
    @CollectionTable(
            name = "flight_schedule_operating_days",
            joinColumns = @JoinColumn(name = "schedule_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    @Builder.Default
    private Set<DayOfWeek> operatingDays = new HashSet<>();

    @Column(nullable = false)
    private LocalDate validFrom;

    /** Null means the schedule runs indefinitely until paused/cancelled. */
    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleStatus status;

    /** Calendar date up to which Flight instances have already been generated. */
    private LocalDate lastGeneratedDate;

    /**
     * How many days ahead each generation run should cover for this schedule,
     * unless a caller explicitly overrides it on a single /generate call.
     * Defaults to 30 at creation time - different schedules (e.g. seasonal
     * charter routes vs. long-running mainline routes) may want different
     * horizons.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer generationDaysAhead = 30;

    /** Why the schedule is currently PAUSED or CANCELLED, e.g. "Runway Maintenance". */
    private String statusReason;

    /** Free-text operator notes accompanying statusReason. */
    @Column(length = 500)
    private String statusRemarks;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = ScheduleStatus.ACTIVE;
        }
        if (generationDaysAhead == null) {
            generationDaysAhead = 30;
        }
    }
}
