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
 * The live email-verification code for one unverified account.
 *
 * <p>Same storage doctrine as {@link PasswordResetToken}: only the SHA-256
 * {@code otpHash} is persisted - the 6-digit code itself exists only in the
 * email - and issuing a new code replaces this row, so the newest email is the
 * only redeemable one. {@code attempts} counts failed guesses; past the cap the
 * code is dead regardless of expiry, because six digits is guessable given
 * unlimited tries and no other defence.
 */
@Entity
@Table(name = "email_verification_otps")
@Getter
@Setter
public class EmailVerificationOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;
}
