package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.LoginRequest;
import com.skybook.praveen.authservice.dto.RegisterRequest;
import com.skybook.praveen.authservice.entity.EmailVerificationOtp;
import com.skybook.praveen.authservice.entity.PasswordResetToken;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The account lifecycle (SECURITY_HARDENING_MODULE.md §6, FRONTEND_MODULE.md
 * "Forgot password"). The properties that carry weight here are negative ones -
 * what a caller must NOT be able to learn, and what must never be persisted in
 * the clear - so most of these assertions are about the paths that fail.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String ALICE = "alice@example.com";
    private static final String STORED_HASH = "$2a$10$stored-hash-for-alice";
    private static final String DUMMY_HASH = "$2a$10$constant-time-placeholder";
    private static final String NEW_HASH = "$2a$10$brand-new-hash";
    private static final String VALID_PASSWORD = "ValidPass123!";
    private static final String BASE_URL = "https://skybook.example";
    private static final long RESET_TTL_MINUTES = 30L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private EmailVerificationOtpRepository emailVerificationOtpRepository;
    @Mock
    private EmailEventProducer emailEventProducer;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void applyDeployProperties() {
        ReflectionTestUtils.setField(authService, "publicBaseUrl", BASE_URL);
        ReflectionTestUtils.setField(authService, "resetTtlMinutes", RESET_TTL_MINUTES);
        ReflectionTestUtils.setField(authService, "otpTtlMinutes", 10L);
        ReflectionTestUtils.setField(authService, "otpMaxAttempts", 5);
        ReflectionTestUtils.setField(authService, "otpResendCooldownSeconds", 60L);
    }

    private static User alice() {
        User user = new User();
        user.setId(7L);
        user.setEmail(ALICE);
        user.setFullName("Alice Smith");
        user.setPassword(STORED_HASH);
        user.setRole(UserRole.USER);
        user.setEmailVerified(true);
        return user;
    }

    /** Pull the 6-digit code back out of the captured verification email. */
    private static String codeIn(EmailEvent event) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(event.getBody());
        assertThat(matcher.find()).as("verification email carries a 6-digit code").isTrue();
        return matcher.group(1);
    }

    /** The same digest the service uses, so the test can assert on what is stored. */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private EmailEvent capturedEmail() {
        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(emailEventProducer).sendEmailEvent(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        void storesTheAccountUnverifiedWithANormalizedEmailAndAHashedPassword() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.empty());
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(STORED_HASH);
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
            when(emailVerificationOtpRepository.findByUserId(any())).thenReturn(Optional.empty());

            String result = authService.register(
                    new RegisterRequest("Alice Smith", "  Alice@Example.COM  ", VALID_PASSWORD));

            assertThat(result).isEqualTo("Verification code sent");

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            // Alice@Example.COM and alice@example.com are one account (§6).
            assertThat(saved.getValue().getEmail()).isEqualTo(ALICE);
            assertThat(saved.getValue().getPassword()).isEqualTo(STORED_HASH);
            assertThat(saved.getValue().getPassword()).isNotEqualTo(VALID_PASSWORD);
            assertThat(saved.getValue().getFullName()).isEqualTo("Alice Smith");
            // Born unverified: sign-in stays refused until the code is redeemed.
            assertThat(saved.getValue().isEmailVerified()).isFalse();
        }

        @Test
        void alwaysCreatesAPlainUserNeverAnAdmin() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.empty());
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(STORED_HASH);
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
            when(emailVerificationOtpRepository.findByUserId(any())).thenReturn(Optional.empty());

            authService.register(new RegisterRequest("Alice Smith", ALICE, VALID_PASSWORD));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getRole()).isEqualTo(UserRole.USER);
        }

        @Test
        void mailsAVerificationCodeAndStoresOnlyItsHash() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.empty());
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(STORED_HASH);
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
            when(emailVerificationOtpRepository.findByUserId(any())).thenReturn(Optional.empty());

            authService.register(new RegisterRequest("Alice Smith", "Alice@Example.COM", VALID_PASSWORD));

            EmailEvent event = capturedEmail();
            assertThat(event.getTo()).isEqualTo(ALICE);
            // The OTP mail, not the welcome mail - "welcome" waits for proof.
            assertThat(event.getType()).isEqualTo(EmailType.EMAIL_VERIFICATION);
            String code = codeIn(event);

            ArgumentCaptor<EmailVerificationOtp> otp =
                    ArgumentCaptor.forClass(EmailVerificationOtp.class);
            verify(emailVerificationOtpRepository).save(otp.capture());
            // Only the digest is persisted; the code itself lives in the email.
            assertThat(otp.getValue().getOtpHash()).isEqualTo(sha256Hex(code));
            assertThat(otp.getValue().getExpiresAt()).isAfter(Instant.now());
            assertThat(otp.getValue().getAttempts()).isZero();
        }

        @Test
        void refusesAnEmailThatIsAlreadyRegisteredAndVerified() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            assertThatThrownBy(() -> authService.register(
                    new RegisterRequest("Impostor", ALICE, VALID_PASSWORD)))
                    .isInstanceOf(EmailAlreadyRegisteredException.class);

            verify(userRepository, never()).save(any());
            verifyNoInteractions(emailEventProducer);
        }

        @Test
        void detectsADuplicateEvenWhenTheCaseDiffersFromTheStoredRow() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            assertThatThrownBy(() -> authService.register(
                    new RegisterRequest("Impostor", "ALICE@EXAMPLE.COM", VALID_PASSWORD)))
                    .isInstanceOf(EmailAlreadyRegisteredException.class);
        }

        @Test
        void letsANewRegistrantTakeOverAnUnverifiedAccount() {
            // Nobody ever proved they own the address, so it is not claimed
            // property: the newest registrant replaces name and password and
            // gets a fresh code. Without this, one abandoned attempt would
            // squat on the address forever.
            User abandoned = alice();
            abandoned.setEmailVerified(false);
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(abandoned));
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(NEW_HASH);
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
            when(emailVerificationOtpRepository.findByUserId(7L)).thenReturn(Optional.empty());

            String result = authService.register(
                    new RegisterRequest("Alice Rewritten", ALICE, VALID_PASSWORD));

            assertThat(result).isEqualTo("Verification code sent");
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getFullName()).isEqualTo("Alice Rewritten");
            assertThat(saved.getValue().getPassword()).isEqualTo(NEW_HASH);
            assertThat(saved.getValue().isEmailVerified()).isFalse();
            assertThat(capturedEmail().getType()).isEqualTo(EmailType.EMAIL_VERIFICATION);
        }

        @Test
        void turnsTheConcurrentDoubleRegisterRaceIntoTheSameConflict() {
            // The findByEmail pre-check passed but the unique index rejected the
            // insert; the caller must see the ordinary 409, not a 500.
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.empty());
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(STORED_HASH);
            when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> authService.register(
                    new RegisterRequest("Alice Smith", ALICE, VALID_PASSWORD)))
                    .isInstanceOf(EmailAlreadyRegisteredException.class);

            // No email of any kind for an account that was never created.
            verifyNoInteractions(emailEventProducer);
        }
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("login")
    class Login {

        @BeforeEach
        void primeTheConstantTimeHash() {
            when(passwordEncoder.encode("constant-time-placeholder")).thenReturn(DUMMY_HASH);
            authService.initConstantTimeHash();
        }

        @Test
        void issuesATokenForTheStoredIdentityWhenThePasswordMatches() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));
            when(passwordEncoder.matches(VALID_PASSWORD, STORED_HASH)).thenReturn(true);
            when(jwtService.generateToken(ALICE, UserRole.USER)).thenReturn("a.jwt.token");

            assertThat(authService.login(new LoginRequest(ALICE, VALID_PASSWORD))).isEqualTo("a.jwt.token");
        }

        @Test
        void mintsTheTokenWithTheRoleHeldOnTheAccountNotOneTheCallerSupplied() {
            User admin = alice();
            admin.setRole(UserRole.ADMIN);
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(admin));
            when(passwordEncoder.matches(VALID_PASSWORD, STORED_HASH)).thenReturn(true);
            when(jwtService.generateToken(ALICE, UserRole.ADMIN)).thenReturn("an.admin.token");

            assertThat(authService.login(new LoginRequest(ALICE, VALID_PASSWORD))).isEqualTo("an.admin.token");
        }

        @Test
        void normalizesTheEmailBeforeLookingTheAccountUp() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));
            when(passwordEncoder.matches(VALID_PASSWORD, STORED_HASH)).thenReturn(true);
            when(jwtService.generateToken(ALICE, UserRole.USER)).thenReturn("a.jwt.token");

            authService.login(new LoginRequest("  Alice@Example.COM  ", VALID_PASSWORD));

            verify(userRepository).findByEmail(ALICE);
        }

        @Test
        void makesAnUnknownUserAndAWrongPasswordIndistinguishable() {
            // The whole point of §6: nothing in the failure tells the caller
            // whether the address has an account.
            when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            Throwable unknownUser = catchThrowable(() ->
                    authService.login(new LoginRequest("ghost@example.com", "wrong-password")));
            Throwable wrongPassword = catchThrowable(() ->
                    authService.login(new LoginRequest(ALICE, "wrong-password")));

            assertThat(unknownUser).isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid email or password");
            assertThat(wrongPassword).isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid email or password");
        }

        @Test
        void runsThePasswordComparisonEvenWhenNoSuchUserExists() {
            // Skipping BCrypt for a missing account would make "no such user"
            // measurably faster than "wrong password" - enumeration by stopwatch.
            when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "wrong-password")))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(passwordEncoder).matches("wrong-password", DUMMY_HASH);
        }

        @Test
        void runsThePasswordComparisonAgainstTheDummyForAGoogleOnlyAccount() {
            // A federated-only account stores password = NULL (SSO_MODULE.md
            // §2.4). BCryptPasswordEncoder short-circuits on a null encoded
            // password without doing BCrypt work, so handing it the real (null)
            // hash would make "Google-only account" measurably faster than
            // "wrong password" - the same stopwatch channel as a missing user,
            // reopened. The dummy hash keeps this failure BCrypt-shaped too.
            User googleOnly = alice();
            googleOnly.setPassword(null);
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(googleOnly));

            assertThatThrownBy(() -> authService.login(new LoginRequest(ALICE, "any-password")))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid email or password");

            verify(passwordEncoder).matches("any-password", DUMMY_HASH);
        }

        @Test
        void failsTheSameGenericWayWhenNoPasswordIsSuppliedAtAll() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            assertThatThrownBy(() -> authService.login(new LoginRequest(ALICE, null)))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid email or password");
        }

        @Test
        void neverMintsATokenForAFailedSignIn() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            assertThatThrownBy(() -> authService.login(new LoginRequest(ALICE, "wrong-password")))
                    .isInstanceOf(InvalidCredentialsException.class);

            verifyNoInteractions(jwtService);
        }

        @Test
        void refusesAnUnverifiedAccountEvenWithTheRightPassword() {
            User unverified = alice();
            unverified.setEmailVerified(false);
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(unverified));
            when(passwordEncoder.matches(VALID_PASSWORD, STORED_HASH)).thenReturn(true);

            // 403, not 401: the credentials are right, and only their owner
            // ever reaches this branch - so it can safely say what is wrong.
            assertThatThrownBy(() -> authService.login(new LoginRequest(ALICE, VALID_PASSWORD)))
                    .isInstanceOf(EmailNotVerifiedException.class);

            verifyNoInteractions(jwtService);
        }
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("email verification")
    class EmailVerification {

        private static final String CODE = "482913";

        private EmailVerificationOtp liveOtp() {
            EmailVerificationOtp otp = new EmailVerificationOtp();
            otp.setId(31L);
            otp.setUserId(7L);
            otp.setOtpHash(sha256Hex(CODE));
            otp.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
            otp.setAttempts(0);
            otp.setLastSentAt(Instant.now().minus(2, ChronoUnit.MINUTES));
            return otp;
        }

        private User unverifiedAlice() {
            User user = alice();
            user.setEmailVerified(false);
            return user;
        }

        @Test
        void theRightCodeActivatesTheAccountAndOnlyThenWelcomesIt() {
            User user = unverifiedAlice();
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(user));
            when(emailVerificationOtpRepository.findByUserId(7L)).thenReturn(Optional.of(liveOtp()));
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            authService.verifyEmail("  Alice@Example.COM  ", CODE);

            assertThat(user.isEmailVerified()).isTrue();
            // Single-use: the redeemed code dies with the redemption.
            verify(emailVerificationOtpRepository).deleteByUserId(7L);
            EmailEvent event = capturedEmail();
            assertThat(event.getType()).isEqualTo(EmailType.REGISTRATION_SUCCESS);
            assertThat(event.getTo()).isEqualTo(ALICE);
        }

        @Test
        void theWrongCodeBurnsAnAttemptAndFailsGenerically() {
            EmailVerificationOtp otp = liveOtp();
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(unverifiedAlice()));
            when(emailVerificationOtpRepository.findByUserId(7L)).thenReturn(Optional.of(otp));

            assertThatThrownBy(() -> authService.verifyEmail(ALICE, "000000"))
                    .isInstanceOf(InvalidVerificationCodeException.class);

            // The failed guess is spent - 6 digits survives 5 tries, not 5000.
            assertThat(otp.getAttempts()).isEqualTo(1);
            verify(emailVerificationOtpRepository).save(otp);
            verifyNoInteractions(emailEventProducer);
        }

        @Test
        void anExpiredCodeIsAsDeadAsAWrongOne() {
            EmailVerificationOtp otp = liveOtp();
            otp.setExpiresAt(Instant.now().minusSeconds(1));
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(unverifiedAlice()));
            when(emailVerificationOtpRepository.findByUserId(7L)).thenReturn(Optional.of(otp));

            assertThatThrownBy(() -> authService.verifyEmail(ALICE, CODE))
                    .isInstanceOf(InvalidVerificationCodeException.class);
        }

        @Test
        void theAttemptCapKillsTheCodeEvenWhenTheGuessIsFinallyRight() {
            EmailVerificationOtp otp = liveOtp();
            otp.setAttempts(5);
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(unverifiedAlice()));
            when(emailVerificationOtpRepository.findByUserId(7L)).thenReturn(Optional.of(otp));

            assertThatThrownBy(() -> authService.verifyEmail(ALICE, CODE))
                    .isInstanceOf(TooManyVerificationAttemptsException.class);
        }

        @Test
        void anUnknownAddressFailsExactlyLikeAWrongCode() {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.verifyEmail("nobody@example.com", CODE))
                    .isInstanceOf(InvalidVerificationCodeException.class);
        }

        @Test
        void reVerifyingAVerifiedAccountIsAQuietSuccess() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            assertThatCode(() -> authService.verifyEmail(ALICE, CODE))
                    .doesNotThrowAnyException();

            verifyNoInteractions(emailVerificationOtpRepository);
        }

        @Test
        void resendMailsAFreshCodeToAnUnverifiedAccount() {
            EmailVerificationOtp otp = liveOtp();
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(unverifiedAlice()));
            when(emailVerificationOtpRepository.findByUserId(7L)).thenReturn(Optional.of(otp));
            when(emailVerificationOtpRepository.save(any())).thenAnswer(call -> call.getArgument(0));

            authService.resendVerification(ALICE);

            EmailEvent event = capturedEmail();
            assertThat(event.getType()).isEqualTo(EmailType.EMAIL_VERIFICATION);
            // The old code is replaced, not joined: the row is reused and the
            // new hash matches the new email's code.
            assertThat(otp.getOtpHash()).isEqualTo(sha256Hex(codeIn(event)));
            assertThat(otp.getAttempts()).isZero();
        }

        @Test
        void resendInsideTheCooldownSendsNothing() {
            EmailVerificationOtp otp = liveOtp();
            otp.setLastSentAt(Instant.now().minusSeconds(5));
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(unverifiedAlice()));
            when(emailVerificationOtpRepository.findByUserId(7L)).thenReturn(Optional.of(otp));

            authService.resendVerification(ALICE);

            verifyNoInteractions(emailEventProducer);
            verify(emailVerificationOtpRepository, never()).save(any());
        }

        @Test
        void resendForUnknownOrVerifiedAccountsIsAnIndistinguishableNoOp() {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            assertThatCode(() -> {
                authService.resendVerification("nobody@example.com");
                authService.resendVerification(ALICE);
            }).doesNotThrowAnyException();

            verifyNoInteractions(emailEventProducer);
        }
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("forgot password")
    class RequestPasswordReset {

        @Test
        void storesOnlyTheHashOfATokenItEmailsInTheClear() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            authService.requestPasswordReset(ALICE);

            ArgumentCaptor<PasswordResetToken> stored = ArgumentCaptor.forClass(PasswordResetToken.class);
            verify(passwordResetTokenRepository).save(stored.capture());

            String rawToken = tokenFromLink(capturedEmail().getBody());
            // A leaked password_reset_tokens table must yield nothing redeemable.
            assertThat(stored.getValue().getTokenHash()).isEqualTo(sha256Hex(rawToken));
            assertThat(stored.getValue().getTokenHash()).isNotEqualTo(rawToken);
            assertThat(stored.getValue().getTokenHash()).hasSize(64);
            assertThat(stored.getValue().getUserId()).isEqualTo(7L);
        }

        @Test
        void givesTheLinkALifetimeTakenFromTheConfiguredTtl() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            Instant before = Instant.now();
            authService.requestPasswordReset(ALICE);
            Instant after = Instant.now();

            ArgumentCaptor<PasswordResetToken> stored = ArgumentCaptor.forClass(PasswordResetToken.class);
            verify(passwordResetTokenRepository).save(stored.capture());
            assertThat(stored.getValue().getExpiresAt())
                    .isBetween(before.plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES),
                            after.plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES));
        }

        @Test
        void voidsAnyEarlierLinkBeforeIssuingTheNewOne() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            authService.requestPasswordReset(ALICE);

            var order = inOrder(passwordResetTokenRepository);
            order.verify(passwordResetTokenRepository).deleteByUserId(7L);
            order.verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        }

        @Test
        void sendsTheResetLinkToTheAccountAddressOnThePublicBaseUrl() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            authService.requestPasswordReset(ALICE);

            EmailEvent event = capturedEmail();
            assertThat(event.getTo()).isEqualTo(ALICE);
            assertThat(event.getType()).isEqualTo(EmailType.FORGOT_PASSWORD);
            assertThat(event.getBody()).contains(BASE_URL + "/reset-password?token=");
            assertThat(event.getBody()).contains(RESET_TTL_MINUTES + " minutes");
        }

        @Test
        void doesNothingObservableForAnAddressWithNoAccount() {
            // Same return, no token, no mail - the caller cannot tell whether the
            // address is registered (§6).
            when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

            assertThatCode(() -> authService.requestPasswordReset("ghost@example.com"))
                    .doesNotThrowAnyException();

            verifyNoInteractions(passwordResetTokenRepository);
            verifyNoInteractions(emailEventProducer);
        }

        @Test
        void normalizesTheEmailBeforeLookingTheAccountUp() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.empty());

            authService.requestPasswordReset("  Alice@Example.COM  ");

            verify(userRepository).findByEmail(ALICE);
        }

        @Test
        void issuesAFreshUnpredictableTokenOnEveryRequest() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice()));

            authService.requestPasswordReset(ALICE);
            authService.requestPasswordReset(ALICE);

            ArgumentCaptor<PasswordResetToken> stored = ArgumentCaptor.forClass(PasswordResetToken.class);
            verify(passwordResetTokenRepository, times(2)).save(stored.capture());
            assertThat(stored.getAllValues().get(0).getTokenHash())
                    .isNotEqualTo(stored.getAllValues().get(1).getTokenHash());
        }

        private String tokenFromLink(String emailBody) {
            int start = emailBody.indexOf("token=") + "token=".length();
            int end = emailBody.indexOf('\n', start);
            String encoded = (end < 0 ? emailBody.substring(start) : emailBody.substring(start, end)).trim();
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("reset password")
    class ResetPassword {

        private static final String RAW_TOKEN = "a-raw-reset-token";

        private PasswordResetToken liveToken() {
            PasswordResetToken token = new PasswordResetToken();
            token.setUserId(7L);
            token.setTokenHash(sha256Hex(RAW_TOKEN));
            token.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
            return token;
        }

        @Test
        void looksTheTokenUpByHashSoTheRawValueIsNeverQueried() {
            when(passwordResetTokenRepository.findByTokenHash(sha256Hex(RAW_TOKEN)))
                    .thenReturn(Optional.of(liveToken()));
            when(userRepository.findById(7L)).thenReturn(Optional.of(alice()));
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(NEW_HASH);

            authService.resetPassword(RAW_TOKEN, VALID_PASSWORD);

            verify(passwordResetTokenRepository).findByTokenHash(sha256Hex(RAW_TOKEN));
            verify(passwordResetTokenRepository, never()).findByTokenHash(RAW_TOKEN);
        }

        @Test
        void replacesThePasswordWithAFreshHash() {
            when(passwordResetTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(liveToken()));
            when(userRepository.findById(7L)).thenReturn(Optional.of(alice()));
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(NEW_HASH);

            authService.resetPassword(RAW_TOKEN, VALID_PASSWORD);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getPassword()).isEqualTo(NEW_HASH);
            assertThat(saved.getValue().getPassword()).isNotEqualTo(VALID_PASSWORD);
        }

        @Test
        void burnsEveryTokenForTheAccountSoTheLinkCannotBeReplayed() {
            when(passwordResetTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(liveToken()));
            when(userRepository.findById(7L)).thenReturn(Optional.of(alice()));
            when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn(NEW_HASH);

            authService.resetPassword(RAW_TOKEN, VALID_PASSWORD);

            verify(passwordResetTokenRepository).deleteByUserId(7L);
        }

        @Test
        void refusesAnUnknownExpiredOrSpentTokenWithOneIndistinguishableError() {
            PasswordResetToken expired = liveToken();
            expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            PasswordResetToken spent = liveToken();
            spent.setUsedAt(Instant.now().minus(1, ChronoUnit.MINUTES));

            when(passwordResetTokenRepository.findByTokenHash(sha256Hex("unknown")))
                    .thenReturn(Optional.empty());
            when(passwordResetTokenRepository.findByTokenHash(sha256Hex("expired")))
                    .thenReturn(Optional.of(expired));
            when(passwordResetTokenRepository.findByTokenHash(sha256Hex("spent")))
                    .thenReturn(Optional.of(spent));

            for (String token : new String[]{"unknown", "expired", "spent"}) {
                assertThatThrownBy(() -> authService.resetPassword(token, VALID_PASSWORD))
                        .isInstanceOf(InvalidResetTokenException.class)
                        .hasMessage("This reset link is invalid or has expired. Please request a new one.");
            }

            verify(userRepository, never()).save(any());
        }

        @Test
        void refusesATokenWhoseAccountHasSinceBeenRemoved() {
            when(passwordResetTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(liveToken()));
            when(userRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.resetPassword(RAW_TOKEN, VALID_PASSWORD))
                    .isInstanceOf(InvalidResetTokenException.class);

            verify(userRepository, never()).save(any());
        }
    }
}
