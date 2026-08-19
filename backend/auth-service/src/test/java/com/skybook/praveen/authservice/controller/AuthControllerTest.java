package com.skybook.praveen.authservice.controller;

import com.skybook.praveen.authservice.exception.EmailAlreadyRegisteredException;
import com.skybook.praveen.authservice.exception.EmailNotVerifiedException;
import com.skybook.praveen.authservice.exception.InvalidCredentialsException;
import com.skybook.praveen.authservice.exception.InvalidResetTokenException;
import com.skybook.praveen.authservice.exception.InvalidVerificationCodeException;
import com.skybook.praveen.authservice.exception.TooManyVerificationAttemptsException;
import com.skybook.praveen.authservice.security.JwtAuthenticationFilter;
import com.skybook.praveen.authservice.security.SessionCookie;
import com.skybook.praveen.authservice.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth surface behaviour (SECURITY_HARDENING_MODULE.md §6): bean-validation on
 * the register/login bodies and the typed-exception -> status mapping in
 * {@link com.skybook.praveen.authservice.exception.GlobalExceptionHandler}.
 * Security filters are disabled (register/login are public) so these assertions
 * isolate validation + advice; the full auth chain is covered by the live E2E.
 */
@WebMvcTest(controllers = AuthController.class,
        // @WebMvcTest auto-registers servlet Filter components; exclude the JWT
        // filter (needs JwtService, not in this slice). Security is off anyway.
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    /**
     * Mocked because it is a @Component with @Value-injected properties, which a
     * @WebMvcTest slice does not create. The cookie's real attributes
     * (httpOnly/Secure/SameSite/Max-Age) are asserted where they actually
     * matter - against the running service - not through a mock that would only
     * echo whatever we told it to say.
     */
    @MockitoBean
    private SessionCookie sessionCookie;

    private static final String VALID_PASSWORD = "ValidPass123!";

    // ---- registration validation (400) -------------------------------------

    @Test
    void register_rejectsWeakPassword() throws Exception {
        // 8 chars, no symbol -> fails @Size(min=12) and the complexity pattern.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"A B\",\"email\":\"a@b.com\",\"password\":\"weakpass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void register_rejectsPasswordMissingSymbolAndDigit() throws Exception {
        // 12+ chars but only letters -> fails the complexity pattern.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"A B\",\"email\":\"a@b.com\",\"password\":\"OnlyLetters\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_rejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"A B\",\"email\":\"not-an-email\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_rejectsBlankFullName() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"\",\"email\":\"a@b.com\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_acceptsValidPayload() throws Exception {
        when(authService.register(any())).thenReturn("User registered successfully");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"A B\",\"email\":\"a@b.com\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    // ---- registration conflict (409) ---------------------------------------

    @Test
    void register_duplicateEmailReturns409() throws Exception {
        when(authService.register(any())).thenThrow(new EmailAlreadyRegisteredException());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"A B\",\"email\":\"a@b.com\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // ---- login validation (400) + bad credentials (401) --------------------

    @Test
    void login_rejectsBlankPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_allowsNonComplexPassword() throws Exception {
        // Old-policy accounts: login only requires @NotBlank, not complexity.
        when(authService.login(any())).thenReturn("a.jwt.token");
        when(sessionCookie.issue(any(), anyBoolean())).thenReturn("skybook_session=a.jwt.token; Path=/; HttpOnly");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"old\"}"))
                .andExpect(status().isOk())
                // The body token stays: API clients (Postman, the e2e suite) read
                // it, and only the browser uses the cookie.
                .andExpect(content().string("a.jwt.token"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void login_badCredentialsReturns401() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_unverifiedEmailReturns403NotThe401() throws Exception {
        // Distinct from bad credentials on purpose: the client routes 403 to
        // the code-entry step, and only the password's owner ever reaches it.
        when(authService.login(any())).thenThrow(new EmailNotVerifiedException());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // ---- email verification -------------------------------------------------

    @Test
    void verifyEmail_returns204AndDelegatesEmailAndCode() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"otp\":\"482913\"}"))
                .andExpect(status().isNoContent());
        verify(authService).verifyEmail("a@b.com", "482913");
    }

    @Test
    void verifyEmail_rejectsANonSixDigitCodeBeforeTheServiceIsInvolved() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"otp\":\"12345\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"otp\":\"abcdef\"}"))
                .andExpect(status().isBadRequest());
        verify(authService, never()).verifyEmail(any(), any());
    }

    @Test
    void verifyEmail_wrongCodeReturnsAGeneric400() throws Exception {
        doThrow(new InvalidVerificationCodeException())
                .when(authService).verifyEmail("a@b.com", "000000");
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"otp\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void verifyEmail_attemptCapReturns429() throws Exception {
        doThrow(new TooManyVerificationAttemptsException())
                .when(authService).verifyEmail("a@b.com", "000000");
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"otp\":\"000000\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void resendVerification_alwaysReturns202() throws Exception {
        // No enumeration: the same answer whether or not the address exists.
        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\"}"))
                .andExpect(status().isAccepted());
        verify(authService).resendVerification("a@b.com");
    }

    // ---- keep-me-signed-in flag reaches the cookie ---------------------------

    @Test
    void login_defaultsToANonPersistentSession() throws Exception {
        when(authService.login(any())).thenReturn("a.jwt.token");
        when(sessionCookie.issue(any(), anyBoolean())).thenReturn("skybook_session=a.jwt.token; Path=/; HttpOnly");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk());

        // Persistence is opt-in: the safer choice on a shared machine.
        verify(sessionCookie).issue("a.jwt.token", false);
    }

    @Test
    void login_passesTheRememberFlagThroughToTheCookie() throws Exception {
        when(authService.login(any())).thenReturn("a.jwt.token");
        when(sessionCookie.issue(any(), anyBoolean())).thenReturn("skybook_session=a.jwt.token; Max-Age=3600");

        mockMvc.perform(post("/api/auth/login")
                        .param("remember", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk());

        verify(sessionCookie).issue("a.jwt.token", true);
    }

    // ---- logout -------------------------------------------------------------

    @Test
    void logout_returnsTheExpiringCookieWithoutTouchingTheAccount() throws Exception {
        when(sessionCookie.expire()).thenReturn("skybook_session=; Path=/; Max-Age=0; HttpOnly");

        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));

        verifyNoInteractions(authService);
    }

    // ---- forgot password ----------------------------------------------------

    @Test
    void forgotPassword_alwaysAccepts() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(authService).requestPasswordReset("a@b.com");
    }

    @Test
    void forgotPassword_answersIdenticallyForAnAddressWithNoAccount() throws Exception {
        // The service no-ops for an unknown address; the response must not differ
        // in any way a caller could use to enumerate registered emails.
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@b.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void forgotPassword_rejectsAMalformedAddress() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- reset password -----------------------------------------------------

    @Test
    void resetPassword_redeemsTheTokenAndReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"raw-token\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        verify(authService).resetPassword("raw-token", VALID_PASSWORD);
    }

    @Test
    void resetPassword_appliesTheRegistrationComplexityPolicyToTheNewPassword() throws Exception {
        // A reset sets a brand-new password, so it must clear the same bar
        // registration does - unlike login, which deliberately does not.
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"raw-token\",\"password\":\"weakpass\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    void resetPassword_rejectsAMissingToken() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_mapsAnUnknownOrExpiredTokenToAGeneric400() throws Exception {
        doThrow(new InvalidResetTokenException())
                .when(authService).resetPassword(anyString(), anyString());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"stale-token\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
