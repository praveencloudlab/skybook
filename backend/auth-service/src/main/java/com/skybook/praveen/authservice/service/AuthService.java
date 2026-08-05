package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.LoginRequest;
import com.skybook.praveen.authservice.dto.RegisterRequest;
import com.skybook.praveen.authservice.entity.PasswordResetToken;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.exception.EmailAlreadyRegisteredException;
import com.skybook.praveen.authservice.exception.InvalidCredentialsException;
import com.skybook.praveen.authservice.exception.InvalidResetTokenException;
import com.skybook.praveen.authservice.producer.EmailEventProducer;
import com.skybook.praveen.authservice.repository.PasswordResetTokenRepository;
import com.skybook.praveen.authservice.repository.UserRepository;
import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.common.event.EmailType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailEventProducer emailEventProducer;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Base URL of the SPA - the reset link points at {@code /reset-password} here. */
    @Value("${app.public-base-url:http://localhost:5173}")
    private String publicBaseUrl;

    /** How long a reset link stays valid. Short by design - it is a one-time key. */
    @Value("${app.password-reset.ttl-minutes:30}")
    private long resetTtlMinutes;

    /**
     * A precomputed hash the login path compares against when the user does not
     * exist, so "unknown user" runs the same BCrypt work as "wrong password" and
     * the two stay timing-indistinguishable (no user enumeration, §6).
     */
    private String dummyPasswordHash;

    @PostConstruct
    void initConstantTimeHash() {
        this.dummyPasswordHash = passwordEncoder.encode("constant-time-placeholder");
    }

    public String register(RegisterRequest request) {

        // Normalize before every lookup/insert (SECURITY_HARDENING_MODULE.md §6)
        // so Alice@X.com and alice@x.com are one account; the DB CHECK enforces
        // it at the storage layer too.
        String email = normalize(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User();
        user.setFullName(request.fullName());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setRole(UserRole.USER);

        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Concurrent double-register: the existsByEmail pre-check passed but
            // the unique index rejected the insert. Translate the race to the
            // same generic 409 (§6).
            throw new EmailAlreadyRegisteredException();
        }

        EmailEvent emailEvent = EmailEvent.builder()
                .to(savedUser.getEmail())
                .subject("Welcome to SkyBook")
                .body("Hi " + savedUser.getFullName() + ", welcome to SkyBook!")
                .type(EmailType.REGISTRATION_SUCCESS)
                .build();

        emailEventProducer.sendEmailEvent(emailEvent);

        return "User registered successfully";
    }

    public String login(LoginRequest request) {

        String email = normalize(request.email());

        User user = userRepository.findByEmail(email).orElse(null);

        // Run BCrypt in both branches (real hash, or the dummy for a missing
        // user) so timing can't distinguish "no such user" from "wrong password".
        //
        // The null check on the stored hash is part of the same defence, not a
        // formality (SSO_MODULE.md §2.4): a Google-only account has password =
        // NULL, and BCryptPasswordEncoder short-circuits on a null/empty encoded
        // password WITHOUT doing BCrypt work - a measurably faster 401 that
        // would tell an attacker "this email exists, via Google". The dummy
        // hash keeps every failure BCrypt-shaped.
        String hashToCheck = (user != null && user.getPassword() != null)
                ? user.getPassword()
                : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        return jwtService.generateToken(user.getEmail(), user.getRole());
    }

    /**
     * Begin a password reset. Always completes the same way whether or not the
     * email belongs to an account (no enumeration, §6): the caller learns
     * nothing from the response. Only when the user exists do we mint a token
     * and send the link.
     *
     * <p>The raw token goes out ONLY in the email; the database keeps just its
     * SHA-256 hash, so a leaked table cannot be redeemed. Any earlier tokens for
     * the user are dropped first, so the newest link is the only live one.
     */
    @Transactional
    public void requestPasswordReset(String rawEmail) {
        String email = normalize(rawEmail);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // Do nothing observable - but return as if we had, so timing and
            // response are identical to the account-exists path.
            log.info("Password reset requested for unknown email - no-op");
            return;
        }

        passwordResetTokenRepository.deleteByUserId(user.getId());

        String rawToken = randomToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(Instant.now().plus(Duration.ofMinutes(resetTtlMinutes)));
        passwordResetTokenRepository.save(token);

        String link = publicBaseUrl + "/reset-password?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

        emailEventProducer.sendEmailEvent(EmailEvent.builder()
                .to(user.getEmail())
                .subject("Reset your SkyBook password")
                .body("Hi " + user.getFullName() + ",\n\n"
                        + "We received a request to reset your SkyBook password. "
                        + "Use the link below within " + resetTtlMinutes + " minutes:\n\n"
                        + link + "\n\n"
                        + "If you didn't ask for this, you can safely ignore this email - "
                        + "your password won't change.")
                .type(EmailType.FORGOT_PASSWORD)
                .build());
    }

    /**
     * Complete a reset: exchange a valid token for a new password. The token is
     * looked up by hash and must be unspent and unexpired; any of unknown,
     * spent, or expired raises the same generic error. On success the password
     * is replaced and every reset token for that user is cleared, so the link
     * cannot be replayed.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(sha256Hex(rawToken))
                .filter(t -> t.isRedeemable(Instant.now()))
                .orElseThrow(InvalidResetTokenException::new);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(InvalidResetTokenException::new);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Single-use: drop this token and any siblings so the link is dead the
        // moment it is redeemed.
        passwordResetTokenRepository.deleteByUserId(user.getId());
        log.info("Password reset completed for user {}", user.getId());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS to be present on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
