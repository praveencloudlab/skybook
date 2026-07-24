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
}
