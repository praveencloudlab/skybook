package com.skybook.praveen.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * A reusable companion profile owned by a user (FRONTEND_MODULE.md Module 14),
 * so booking for family or colleagues doesn't retype passport details every
 * time. Owned by {@code userId} - every query is scoped to the owner, so one
 * user can never read or edit another's travellers.
 */
@Entity
@Table(name = "saved_travellers")
@Getter
@Setter
public class SavedTraveller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String title;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** ISO-3166 alpha-3, e.g. GBR. */
    private String nationality;

    @Column(name = "passport_number")
    private String passportNumber;

    @Column(name = "passport_expiry")
    private LocalDate passportExpiry;
}
