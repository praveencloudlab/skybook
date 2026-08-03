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
 * A single-use, short-lived password-reset grant (FRONTEND_MODULE.md - "Forgot
 * password").
 *
 * <p>Only the SHA-256 {@code tokenHash} is persisted; the raw token exists only
 * in the emailed link, so a database leak yields nothing redeemable. A token is
 * spent by stamping {@code usedAt}, and it is valid only while {@code now} is
 * before {@code expiresAt} and {@code usedAt} is null.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public boolean isRedeemable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }
}
