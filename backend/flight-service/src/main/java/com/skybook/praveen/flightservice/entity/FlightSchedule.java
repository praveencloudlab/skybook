package com.skybook.praveen.flightservice.entity;

import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * A recurring flight template, e.g. "AA100 flies LAX -> JFK every Mon/Wed/Fri
 * at 08:00 between 2026-07-01 and 2026-12-31". Concrete {@link Flight}
 * instances are generated from this template on a rolling basis.
 */
@Entity
@Table(name = "flight_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;

        if (status == null) {
            status = ScheduleStatus.ACTIVE;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
