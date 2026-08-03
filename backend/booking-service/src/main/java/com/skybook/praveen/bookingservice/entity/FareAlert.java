package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.bookingservice.enums.TravelClass;
import com.skybook.praveen.common.entity.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One watched fare (passenger features): route + date + cabin, owned by the
 * subject who created it (the subject IS the email - that's where alerts
 * go). lastNotifiedFare is the last fare the owner was told about; the sweep
 * only mails when the current deterministic fare differs from it.
 */
@Entity
@Table(name = "fare_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FareAlert extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_subject", nullable = false, updatable = false)
    private String ownerSubject;

    @Column(name = "origin_airport_code", nullable = false, length = 3, updatable = false)
    private String originAirportCode;

    @Column(name = "destination_airport_code", nullable = false, length = 3, updatable = false)
    private String destinationAirportCode;

    @Column(name = "travel_date", nullable = false, updatable = false)
    private LocalDate travelDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "travel_class", nullable = false, length = 20, updatable = false)
    private TravelClass travelClass;

    @Column(name = "last_notified_fare")
    private BigDecimal lastNotifiedFare;

    @Column(nullable = false)
    private boolean active = true;
}
