package com.skybook.praveen.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A machine caller allowed to obtain {@code ROLE_SERVICE} tokens
 * (SECURITY_HARDENING_MODULE.md §3.3/§4.5). The {@code client_id} is the token
 * {@code sub}; {@code secretHash} is a BCrypt hash (never plaintext);
 * {@code allowedAudiences} is the comma-separated set of services this client
 * may target.
 */
@Entity
@Table(name = "service_clients")
@Getter
@Setter
public class ServiceClient {

    @Id
    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "secret_hash", nullable = false, length = 100)
    private String secretHash;

    @Column(name = "allowed_audiences", nullable = false, length = 500)
    private String allowedAudiences;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Whether this client may mint browser-facing GUEST sessions
     * (GUEST_CHECKIN_MODULE.md §3.1). A separate, default-false grant - a
     * bigger privilege than requesting machine tokens for oneself, held only
     * by the service that can verify reference + surname against the data.
     */
    @Column(name = "may_issue_guest_tokens", nullable = false)
    private boolean mayIssueGuestTokens = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Parsed, trimmed set of audiences this client may request a token for. */
    public Set<String> allowedAudienceSet() {
        return Arrays.stream(allowedAudiences.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean mayTarget(String audience) {
        return allowedAudienceSet().contains(audience);
    }
}
