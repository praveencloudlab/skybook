package com.skybook.praveen.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    // Stored already normalized (lower(trim())) - the DB also enforces a CHECK
    // (SECURITY_HARDENING_MODULE.md §4.3/§6), so a direct write can't bypass it.
    @Column(nullable = false, unique = true)
    private String email;

    private String password;

    // Authority tier (§4.1). Non-null; the V2 migration backfills every
    // pre-branch row to USER.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    // False until the account redeems the OTP mailed at registration; login is
    // refused until then. SSO accounts are born true (Google already verified
    // the address), and the V9 migration grandfathers every pre-feature row.
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    // Passenger profile (FRONTEND_MODULE.md Module 14). All nullable - an account
    // carries none of this until the traveller fills it in.
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** ISO-3166 alpha-3, e.g. GBR. */
    private String nationality;

    @Column(name = "passport_number")
    private String passportNumber;

    @Column(name = "passport_expiry")
    private LocalDate passportExpiry;

    // Account-level preferences (nullable = the user never chose; the client
    // keeps its own default until they do).
    @Column(name = "preferred_language", length = 5)
    private String preferredLanguage;

    @Column(name = "preferred_currency", length = 3)
    private String preferredCurrency;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;
}
