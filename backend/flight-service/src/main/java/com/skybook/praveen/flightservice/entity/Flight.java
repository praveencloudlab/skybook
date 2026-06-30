package com.skybook.praveen.flightservice.entity;

import com.skybook.praveen.flightservice.enums.FlightStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "flights",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_flight_number_departure_time",
                columnNames = {"flightNumber", "departureTime"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Flight numbers repeat across days for recurring schedules (e.g. AA100
    // flies daily), so uniqueness is enforced on (flightNumber, departureTime)
    // above rather than on this column alone.
    @Column(nullable = false, length = 10)
    private String flightNumber;

    @Column(nullable = false, length = 5)
    private String airlineCode;

    @Column(nullable = false, length = 3)
    private String originAirportCode;

    @Column(nullable = false, length = 3)
    private String destinationAirportCode;

    @Column(nullable = false)
    private LocalDateTime departureTime;

    @Column(nullable = false)
    private LocalDateTime arrivalTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status;

    // Set when this Flight instance was generated from a FlightSchedule.
    // Null for flights created manually via the Flight Management APIs.
    @Column(name = "schedule_id")
    private Long scheduleId;

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
            status = FlightStatus.SCHEDULED;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
