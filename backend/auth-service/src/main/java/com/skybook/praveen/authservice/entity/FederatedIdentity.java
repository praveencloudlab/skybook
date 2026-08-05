package com.skybook.praveen.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A link between an external identity and a SkyBook account (SSO_MODULE.md
 * §4.1). Keyed on {@code (provider, subject)} - never on email, because the
 * provider's subject is stable for life while the email on the external
 * account can change. {@code emailAtLink} records what the address was when
 * the link was made; it is forensic, never a lookup key.
 */
@Entity
@Table(name = "federated_identities")
@Getter
@Setter
public class FederatedIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false)
    private String subject;

    @Column(name = "email_at_link", nullable = false)
    private String emailAtLink;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt = Instant.now();
}
