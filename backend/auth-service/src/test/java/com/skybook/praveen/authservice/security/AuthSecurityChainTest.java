package com.skybook.praveen.authservice.security;

import com.skybook.praveen.authservice.config.SecurityConfig;
import com.skybook.praveen.authservice.controller.AuthController;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.service.AuthService;
import com.skybook.praveen.authservice.service.CustomUserDetailsService;
import com.skybook.praveen.authservice.service.JwtService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two ordered filter chains (SECURITY_HARDENING_MODULE.md §3.3) exercised as
 * a chain, not as a bean graph: which paths are open without a token, which are
 * not, and that the JWT filter is confined to the application chain so it can
 * never authenticate the client-credential endpoint.
 *
 * <p>Security filters are deliberately ON here - the sibling
 * {@link com.skybook.praveen.authservice.controller.AuthControllerTest} turns
 * them off to isolate validation, which is exactly why the authorization rules
 * need their own coverage.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SessionCookie.class})
@TestPropertySource(properties = "jwt.expiration=3600000")
class AuthSecurityChainTest {

    private static final String GOOD_TOKEN = "a.good.token";
    private static final String ALICE = "alice@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;
    @MockitoBean
    private ServiceClientDetailsService serviceClientDetailsService;
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    /** Makes {@link #GOOD_TOKEN} authenticate as Alice, the way a real RS256 token would. */
    private void tokenResolvesToAlice(UserRole role) {
        User alice = new User();
        alice.setEmail(ALICE);
        alice.setPassword("$2a$10$stored-hash");
        alice.setRole(role);

        when(jwtService.extractUsername(GOOD_TOKEN)).thenReturn(ALICE);
        when(customUserDetailsService.loadUserByUsername(ALICE)).thenReturn(new CustomUserDetails(alice));
        when(jwtService.isTokenValid(GOOD_TOKEN, ALICE)).thenReturn(true);
    }

    @Nested
    @DisplayName("pre-authentication paths stay open")
    class PublicPaths {

        @Test
        void registerIsReachableWithoutAToken() throws Exception {
            when(authService.register(any())).thenReturn("User registered successfully");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"A B\",\"email\":\"a@b.com\",\"password\":\"ValidPass123!\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        void loginIsReachableWithoutATokenAndSetsTheSessionCookie() throws Exception {
            when(authService.login(any())).thenReturn(GOOD_TOKEN);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE,
                            Matchers.containsString("HttpOnly")));
        }

        @Test
        void logoutIsReachableWithoutATokenSoALapsedSessionCanStillBeCleared() throws Exception {
            // Requiring a valid token to log out would strand a user whose token
            // already expired with a cookie they cannot remove.
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE,
                            Matchers.containsString("Max-Age=0")));
        }

        @Test
        void forgotPasswordIsReachableWithoutAToken() throws Exception {
            mockMvc.perform(post("/api/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\"}"))
                    .andExpect(status().isAccepted());
        }

        @Test
        void resetPasswordIsReachableWithoutAToken() throws Exception {
            mockMvc.perform(post("/api/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"raw-token\",\"password\":\"ValidPass123!\"}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        void livenessProbesAreNotGatedBehindAToken() throws Exception {
            // No handler exists in this slice, so 404 is the proof that matters:
            // the request got past authorization rather than being refused.
            mockMvc.perform(get("/livez")).andExpect(status().isNotFound());
            mockMvc.perform(get("/readyz")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("/me answers only a caller who proved who they are")
    class CurrentUser {

        @Test
        void refusesAnAnonymousCaller() throws Exception {
            // Answering "who are you" to someone who presented nothing is the one
            // thing this endpoint must never do.
            //
            // The status is 403, NOT the 401 the service-token chain returns: the
            // application chain configures no authenticationEntryPoint, so Spring
            // Security falls back to Http403ForbiddenEntryPoint even though the
            // caller is unauthenticated rather than under-privileged. Pinned here
            // as the current behaviour so a deliberate change to 401 shows up as a
            // failing test rather than a silent one.
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void refusesACallerWhoseTokenDoesNotValidate() throws Exception {
            when(jwtService.extractUsername("a.bad.token")).thenReturn(ALICE);
            when(customUserDetailsService.loadUserByUsername(ALICE))
                    .thenThrow(new UsernameNotFoundException("gone"));

            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer a.bad.token"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void refusesACallerWhoseTokenIsUnparseable() throws Exception {
            when(jwtService.extractUsername("garbage")).thenThrow(new IllegalArgumentException("bad token"));

            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void returnsTheSubjectAndRolesTheServerItselfValidated() throws Exception {
            tokenResolvesToAlice(UserRole.USER);

            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + GOOD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value(ALICE))
                    .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
        }

        @Test
        void reportsTheAdminRoleForAnAdministrator() throws Exception {
            tokenResolvesToAlice(UserRole.ADMIN);

            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + GOOD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"));
        }
    }

    @Nested
    @DisplayName("everything not named public requires a token")
    class ProtectedPaths {

        @Test
        void refusesAnAnonymousCallerOnAnEndpointThatIsNotOnTheAllowList() throws Exception {
            mockMvc.perform(get("/api/auth/profile"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void admitsTheSameCallerOnceTheyPresentAValidToken() throws Exception {
            tokenResolvesToAlice(UserRole.USER);

            mockMvc.perform(get("/api/auth/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + GOOD_TOKEN))
                    .andExpect(status().isOk());
        }

        @Test
        void ignoresAnAuthorizationHeaderThatIsNotABearerToken() throws Exception {
            mockMvc.perform(get("/api/auth/profile").header(HttpHeaders.AUTHORIZATION, "Basic Ym9iOnBhc3M="))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("the client-credential chain is separate from the JWT chain")
    class ServiceTokenChain {

        @Test
        void answers401WithNoCredentialAtAll() throws Exception {
            // 401, not the 403 an unconfigured entry point would give (§6): a
            // machine caller getting its FIRST token has nothing else to present.
            mockMvc.perform(post("/api/auth/service-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"audience\":\"inventory-service\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void answersTheSame401ForAnUnknownClient() throws Exception {
            when(serviceClientDetailsService.loadUserByUsername(anyString()))
                    .thenThrow(new UsernameNotFoundException("Unknown service client"));

            mockMvc.perform(post("/api/auth/service-token")
                            .header(HttpHeaders.AUTHORIZATION, "Basic Z2hvc3Q6c2VjcmV0")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"audience\":\"inventory-service\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void refusesABearerTokenHere() throws Exception {
            // The JWT filter's servlet-container registration is disabled, so it
            // runs only inside the application chain. A ROLE_SERVICE token can
            // therefore never be used to mint another one.
            tokenResolvesToAlice(UserRole.ADMIN);

            mockMvc.perform(post("/api/auth/service-token")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + GOOD_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"audience\":\"inventory-service\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("session cookie issuance on the real login path")
    class SessionCookieOnLogin {

        @Test
        void omitsMaxAgeUnlessKeepMeSignedInWasAskedFor() throws Exception {
            when(authService.login(any())).thenReturn(GOOD_TOKEN);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE,
                            Matchers.not(Matchers.containsString("Max-Age"))));
        }

        @Test
        void persistsTheCookieWhenKeepMeSignedInWasAskedFor() throws Exception {
            when(authService.login(any())).thenReturn(GOOD_TOKEN);

            mockMvc.perform(post("/api/auth/login")
                            .param("remember", "true")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE,
                            Matchers.containsString("Max-Age=3600")));
        }
    }
}
