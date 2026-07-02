package com.skybook.praveen.bookingservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * A traveler - separate from Customer (the account holder / who's paying,
 * see docs/BOOKING_SERVICE_MODULE.md section 3.7). A Customer may book for
 * family members who are Passengers but never have their own account.
 *
 * v1 creates a fresh row per booking rather than deduplicating travelers
 * across bookings by passport number - see the open question in section 3.6
 * of the design doc.
 */
@Entity
@Table(name = "passengers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Passenger extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 10)
    private String title;

    @Column(nullable = false)
    private String firstName;

    private String middleName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private LocalDate dob;

    @Column(length = 20)
    private String gender;

    @Column(nullable = false, length = 3)
    private String nationality;

    // Sensitive PII - excluded from the default response DTO's toString-heavy
    // logging paths; consider column-level encryption before production use.
    @Column(nullable = false, length = 20)
    private String passportNumber;

    @Column(nullable = false)
    private LocalDate passportExpiry;

    private String email;

    private String phone;
}
