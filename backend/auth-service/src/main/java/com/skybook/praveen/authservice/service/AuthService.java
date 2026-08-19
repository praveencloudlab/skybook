package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.LoginRequest;
import com.skybook.praveen.authservice.dto.RegisterRequest;
import com.skybook.praveen.authservice.entity.PasswordResetToken;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.entity.EmailVerificationOtp;
import com.skybook.praveen.authservice.exception.EmailAlreadyRegisteredException;
import com.skybook.praveen.authservice.exception.EmailNotVerifiedException;
import com.skybook.praveen.authservice.exception.InvalidCredentialsException;
import com.skybook.praveen.authservice.exception.InvalidResetTokenException;
import com.skybook.praveen.authservice.exception.InvalidVerificationCodeException;
import com.skybook.praveen.authservice.exception.TooManyVerificationAttemptsException;
import com.skybook.praveen.authservice.producer.EmailEventProducer;
import com.skybook.praveen.authservice.repository.EmailVerificationOtpRepository;
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
    private final EmailVerificationOtpRepository emailVerificationOtpRepository;
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

    /** How long a registration verification code stays redeemable. */
    @Value("${app.email-verification.ttl-minutes:10}")
    private long otpTtlMinutes;

    /** Failed guesses one code survives before only a fresh code will do. */
    @Value("${app.email-verification.max-attempts:5}")
    private int otpMaxAttempts;

    /** Floor between two verification emails to the same address. */
    @Value("${app.email-verification.resend-cooldown-seconds:60}")
    private long otpResendCooldownSeconds;

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

    @Transactional
    public String register(RegisterRequest request) {

        // Normalize before every lookup/insert (SECURITY_HARDENING_MODULE.md §6)
        // so Alice@X.com and alice@x.com are one account; the DB CHECK enforces
        // it at the storage layer too.
        String email = normalize(request.email());

        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            // A VERIFIED account is claimed property - 409, same as ever. An
            // UNVERIFIED row is not: nobody ever proved they own that address,
            // so the newest registrant simply takes it over (name and password
            // replaced, fresh code sent). Without this, one abandoned attempt
            // would squat on the address forever - and the real owner could
            // never register.
            if (existing.isEmailVerified()) {
                throw new EmailAlreadyRegisteredException();
            }
            existing.setFullName(request.fullName());
            existing.setPassword(passwordEncoder.encode(request.password()));
            userRepository.save(existing);
            issueVerificationOtp(existing);
            return "Verification code sent";
        }

        User user = new User();
        user.setFullName(request.fullName());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setRole(UserRole.USER);
        user.setEmailVerified(false);

        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Concurrent double-register: the findByEmail pre-check passed but
            // the unique index rejected the insert. Translate the race to the
            // same generic 409 (§6).
            throw new EmailAlreadyRegisteredException();
        }

        issueVerificationOtp(savedUser);

        return "Verification code sent";
    }

    /**
     * Mint and mail the 6-digit code for an unverified account. Replaces any
     * outstanding code (one live code per user, newest wins - the same doctrine
     * as reset tokens), stores only its SHA-256, and respects the resend
     * cooldown so the mailbox cannot be flooded by re-posting the form.
     */
    private void issueVerificationOtp(User user) {
        Instant now = Instant.now();

        EmailVerificationOtp existing = emailVerificationOtpRepository
                .findByUserId(user.getId()).orElse(null);
        if (existing != null
                && existing.getLastSentAt().plusSeconds(otpResendCooldownSeconds).isAfter(now)) {
            // Inside the cooldown the outstanding code stands; the caller's
            // response is indistinguishable, so this is not a probe surface.
            log.info("Verification code for user {} still inside cooldown - not resent", user.getId());
            return;
        }

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));

        EmailVerificationOtp otp = existing != null ? existing : new EmailVerificationOtp();
        otp.setUserId(user.getId());
        otp.setOtpHash(sha256Hex(code));
        otp.setExpiresAt(now.plus(Duration.ofMinutes(otpTtlMinutes)));
        otp.setAttempts(0);
        otp.setLastSentAt(now);
        emailVerificationOtpRepository.save(otp);

        emailEventProducer.sendEmailEvent(EmailEvent.builder()
                .to(user.getEmail())
                .subject("Your SkyBook verification code: " + code)
                .body("Hi " + user.getFullName() + ",\n\n"
                        + "Your SkyBook verification code is:\n\n"
                        + "    " + code + "\n\n"
                        + "Enter it within " + otpTtlMinutes + " minutes to activate your account.\n\n"
                        + "If you didn't create a SkyBook account, you can safely ignore this email.")
                .type(EmailType.EMAIL_VERIFICATION)
                .build());
    }

    /**
     * Redeem the emailed code and activate the account. Unknown address, no
     * outstanding code, expired code and wrong digits all raise the same
     * generic 400 (no enumeration); the attempt counter turns a 6-digit space
     * from guessable into a 5-shot lottery. Already-verified is a quiet
     * success - a double-submit of the right code should not scold anyone.
     *
     * <p>{@code noRollbackFor} is what makes the attempt counter REAL: these
     * exceptions are the method's normal answers, and letting them roll the
     * transaction back would silently discard the {@code attempts} increment -
     * leaving the code guessable forever (caught live, not in the mocks).
     */
    @Transactional(noRollbackFor = {
            InvalidVerificationCodeException.class,
            TooManyVerificationAttemptsException.class})
    public void verifyEmail(String rawEmail, String code) {
        String email = normalize(rawEmail);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw new InvalidVerificationCodeException();
        }
        if (user.isEmailVerified()) {
            return;
        }

        EmailVerificationOtp otp = emailVerificationOtpRepository
                .findByUserId(user.getId())
                .orElseThrow(InvalidVerificationCodeException::new);

        if (otp.getAttempts() >= otpMaxAttempts) {
            throw new TooManyVerificationAttemptsException();
        }
        if (Instant.now().isAfter(otp.getExpiresAt())) {
            throw new InvalidVerificationCodeException();
        }
        if (!otp.getOtpHash().equals(sha256Hex(code))) {
            otp.setAttempts(otp.getAttempts() + 1);
            emailVerificationOtpRepository.save(otp);
            throw new InvalidVerificationCodeException();
        }

        user.setEmailVerified(true);
        userRepository.save(user);
        emailVerificationOtpRepository.deleteByUserId(user.getId());

        // The welcome mail belongs HERE, not at registration: "welcome" to an
        // address nobody has proven they own is spam at best.
        emailEventProducer.sendEmailEvent(EmailEvent.builder()
                .to(user.getEmail())
                .subject("Welcome to SkyBook")
                .body("Hi " + user.getFullName() + ", welcome to SkyBook!")
                .type(EmailType.REGISTRATION_SUCCESS)
                .build());

        log.info("Email verified for user {}", user.getId());
    }

    /**
     * Send a fresh code. Always completes identically whether the address has
     * an unverified account, a verified one, or none at all (no enumeration,
     * same shape as {@link #requestPasswordReset}); only the first case
     * actually mails anything.
     */
    @Transactional
    public void resendVerification(String rawEmail) {
        String email = normalize(rawEmail);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.isEmailVerified()) {
            log.info("Verification resend requested for {} account - no-op",
                    user == null ? "unknown" : "already-verified");
            return;
        }
        issueVerificationOtp(user);
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

        // Checked only AFTER the credentials pass: a 403 here is information,
        // and only the account's owner has earned it. Failing before the
        // password check would let anyone probe which addresses are registered.
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
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
