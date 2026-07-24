package com.skybook.praveen.authservice.controller;

import com.skybook.praveen.authservice.exception.EmailAlreadyRegisteredException;
import com.skybook.praveen.authservice.exception.InvalidCredentialsException;
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
        when(sessionCookie.issue(any())).thenReturn("skybook_session=a.jwt.token; Path=/; HttpOnly");

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
}
