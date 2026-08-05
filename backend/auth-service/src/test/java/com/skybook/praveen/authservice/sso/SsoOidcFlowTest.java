package com.skybook.praveen.authservice.sso;

import com.skybook.praveen.authservice.config.JwtProperties;
import com.skybook.praveen.authservice.config.SecurityConfig;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.security.JwtAuthenticationFilter;
import com.skybook.praveen.authservice.security.SessionCookie;
import com.skybook.praveen.authservice.security.ServiceClientDetailsService;
import com.skybook.praveen.authservice.service.CustomUserDetailsService;
import com.skybook.praveen.authservice.service.JwtService;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OIDC flow end to end against a stubbed Google (SSO_MODULE.md §8): the
 * real Spring OAuth2 filters, the real cookie repository, the real success and
 * failure handlers, the real RS256 JwtService - only the IdP itself and the
 * account decision tree (unit-tested in {@link SsoAccountServiceTest}) are
 * stand-ins. A MockWebServer plays Google's token and JWKS endpoints, so CI
 * proves the artifact's whole SSO path with no external dependency.
 */
@WebMvcTest(controllers = SsoProvidersController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SessionCookie.class,
        JwtService.class, SsoStateCrypto.class, SsoCookieAuthorizationRequestRepository.class,
        SsoSuccessHandler.class, SsoFailureHandler.class,
        SsoOidcFlowTest.StubGoogle.class})
@EnableConfigurationProperties({JwtProperties.class, SsoProperties.class})
class SsoOidcFlowTest {

    private static final String SUB = "google-sub-1108";
    private static final String EMAIL = "bob@example.com";
    private static final String KID = "test-kid";
    private static final KeyPair KEYS = generateKeys();
    private static final MockWebServer GOOGLE = startStub();
    /** One string for registration issuerUri AND the id_token iss claim - they must match exactly. */
    private static final String ISSUER = GOOGLE.url("/").toString();

    /** The token endpoint's next response body, set per test; /certs is static. */
    private static volatile String tokenResponseJson = "{}";
    private static volatile String lastTokenRequestBody = "";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SsoAccountService accounts;
    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;
    @MockitoBean
    private ServiceClientDetailsService serviceClientDetailsService;
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("jwt.private-key", () -> pem("PRIVATE KEY", KEYS.getPrivate().getEncoded()));
        registry.add("jwt.public-key", () -> pem("PUBLIC KEY", KEYS.getPublic().getEncoded()));
        registry.add("jwt.issuer", () -> "skybook-auth-test");
        registry.add("jwt.audience", () -> "skybook-api-test");
        registry.add("jwt.expiration", () -> "3600000");
        // Announced providers come from the property; the oauth2 machinery
        // itself comes from StubGoogle's beans.
        registry.add("skybook.sso.google.client-id", () -> "test-client");
    }

    @TestConfiguration
    static class StubGoogle {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            // Mirrors SsoGoogleConfig's registration, pointed at the stub. No
            // userinfo URI on purpose: claims come from the id_token alone.
            ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                    .clientId("test-client")
                    .clientSecret("test-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/api/auth/oauth2/callback/google")
                    .scope(Set.of("openid", "profile", "email"))
                    .authorizationUri(GOOGLE.url("/authorize").toString())
                    .tokenUri(GOOGLE.url("/token").toString())
                    .jwkSetUri(GOOGLE.url("/certs").toString())
                    .issuerUri(ISSUER)
                    .userNameAttributeName("sub")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }

        @Bean
        OAuth2AuthorizationRequestResolver authorizationRequestResolver(
                ClientRegistrationRepository clientRegistrationRepository) {
            DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                    clientRegistrationRepository, "/api/auth/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            return resolver;
        }
    }

    // ------------------------------------------------------------------ helpers

    private record StartLeg(String state, String nonce, String codeChallenge, String pendingCookie) {
    }

    private StartLeg start(String... params) throws Exception {
        var request = get("/api/auth/oauth2/authorization/google");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(GOOGLE.url("/authorize").toString());
        UriComponents uri = UriComponentsBuilder.fromUriString(location).build();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(SsoCookieAuthorizationRequestRepository.COOKIE_NAME + "=");
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        // The Location's query values are percent-encoded - and Spring's state
        // is PADDED Base64 ("=="), which encodes to %3D%3D. Replaying it
        // encoded would fail the sealed-state comparison for reasons that have
        // nothing to do with the code under test; a real browser hands the
        // server the decoded form, so this does too.
        return new StartLeg(
                decoded(uri, "state"),
                decoded(uri, "nonce"),
                decoded(uri, "code_challenge"),
                cookieValue);
    }

    private static String decoded(UriComponents uri, String name) {
        String value = uri.getQueryParams().getFirst(name);
        return value == null ? null
                : java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A Google id_token for the stub: signed with the test key the JWKS endpoint publishes. */
    private static String idToken(String nonce) {
        Date now = new Date();
        return Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER)
                .subject(SUB)
                .audience().add("test-client").and()
                .claim("email", EMAIL)
                .claim("email_verified", true)
                .claim("name", "Bob Jones")
                .claim("nonce", nonce == null ? "" : nonce)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 300_000))
                .signWith(KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static void primeTokenEndpoint(String nonce) {
        tokenResponseJson = """
                {"access_token":"stub-access-token","token_type":"Bearer","expires_in":3600,
                 "scope":"openid profile email","id_token":"%s"}""".formatted(idToken(nonce));
    }

    private User bob() {
        User user = new User();
        user.setId(41L);
        user.setEmail(EMAIL);
        user.setFullName("Bob Jones");
        user.setRole(UserRole.USER);
        return user;
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("the start leg carries state, nonce, PKCE and the sealed cookie")
    void startLegCarriesTheFullProtocolSurface() throws Exception {
        StartLeg leg = start("remember", "true", "returnTo", "/trips");

        assertThat(leg.state()).isNotBlank();
        assertThat(leg.nonce()).isNotBlank();
        // PKCE S256: a leaked code alone must be worthless (§3.4).
        assertThat(leg.codeChallenge()).isNotBlank();
        assertThat(leg.pendingCookie()).isNotBlank();
    }

    @Test
    @DisplayName("the full journey: Google identity in, SkyBook session out")
    void fullJourneySignsInAndHonoursRemember() throws Exception {
        when(accounts.resolve(anyString(), anyString(), anyBoolean(), any())).thenReturn(bob());

        StartLeg leg = start("remember", "true", "returnTo", "/trips");
        primeTokenEndpoint(leg.nonce());

        MvcResult result = mockMvc.perform(get("/api/auth/oauth2/callback/google")
                        .param("code", "stub-code")
                        .param("state", leg.state())
                        .cookie(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME, leg.pendingCookie())))
                .andExpect(redirectedUrl("/trips"))
                .andReturn();

        // The exchange point held: the cookie carries OUR RS256 token, minted
        // for the resolved account, persistent because remember was carried.
        String sessionCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SessionCookie.NAME + "="))
                .findFirst().orElseThrow();
        assertThat(sessionCookie).contains("HttpOnly").contains("Max-Age=");
        String token = sessionCookie.substring(sessionCookie.indexOf('=') + 1, sessionCookie.indexOf(';'));
        assertThat(jwtService.extractUsername(token)).isEqualTo(EMAIL);

        // PKCE completed: the verifier travelled to the token endpoint.
        assertThat(lastTokenRequestBody).contains("code_verifier=");
    }

    @Test
    @DisplayName("without remember, the session cookie is browser-session scoped")
    void withoutRememberTheCookieHasNoMaxAge() throws Exception {
        when(accounts.resolve(anyString(), anyString(), anyBoolean(), any())).thenReturn(bob());

        StartLeg leg = start();
        primeTokenEndpoint(leg.nonce());

        MvcResult result = mockMvc.perform(get("/api/auth/oauth2/callback/google")
                        .param("code", "stub-code")
                        .param("state", leg.state())
                        .cookie(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME, leg.pendingCookie())))
                .andExpect(redirectedUrl("/"))
                .andReturn();

        String sessionCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SessionCookie.NAME + "="))
                .findFirst().orElseThrow();
        assertThat(sessionCookie).doesNotContain("Max-Age");
    }

    @Test
    @DisplayName("an unverified email gets its own explainable outcome")
    void unverifiedEmailRedirectsWithItsOwnCode() throws Exception {
        when(accounts.resolve(anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new SsoEmailUnverifiedException());

        StartLeg leg = start();
        primeTokenEndpoint(leg.nonce());

        mockMvc.perform(get("/api/auth/oauth2/callback/google")
                        .param("code", "stub-code")
                        .param("state", leg.state())
                        .cookie(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME, leg.pendingCookie())))
                .andExpect(redirectedUrl("/login?error=sso_email_unverified"));
    }

    @Test
    @DisplayName("cancelling at Google is not an error, and reads differently")
    void cancellationAtGoogleReadsAsCancelled() throws Exception {
        StartLeg leg = start();

        mockMvc.perform(get("/api/auth/oauth2/callback/google")
                        .param("error", "access_denied")
                        .param("state", leg.state())
                        .cookie(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME, leg.pendingCookie())))
                .andExpect(redirectedUrl("/login?error=sso_cancelled"));
    }

    @Test
    @DisplayName("a forged state fails closed and generically")
    void aForgedStateFailsGenerically() throws Exception {
        StartLeg leg = start();
        primeTokenEndpoint(leg.nonce());

        mockMvc.perform(get("/api/auth/oauth2/callback/google")
                        .param("code", "stub-code")
                        .param("state", "forged-state")
                        .cookie(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME, leg.pendingCookie())))
                .andExpect(redirectedUrl("/login?error=sso_failed"));
    }

    @Test
    @DisplayName("a callback with no pending cookie fails the same generic way")
    void aMissingPendingCookieFailsGenerically() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/callback/google")
                        .param("code", "stub-code")
                        .param("state", "whatever"))
                .andExpect(redirectedUrl("/login?error=sso_failed"));
    }

    @Test
    @DisplayName("provider discovery announces google when configured")
    void providersAnnouncesGoogle() throws Exception {
        mockMvc.perform(get("/api/auth/sso/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("google"));
    }

    // -------------------------------------------------------------- stub plumbing

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + type + "-----";
    }

    /** JWKS entry for the test public key, the shape /certs serves. */
    private static String jwks() {
        RSAPublicKey publicKey = (RSAPublicKey) KEYS.getPublic();
        return """
                {"keys":[{"kty":"RSA","alg":"RS256","use":"sig","kid":"%s","n":"%s","e":"%s"}]}"""
                .formatted(KID, b64Url(publicKey.getModulus()), b64Url(publicKey.getPublicExponent()));
    }

    private static String b64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        // BigInteger prepends a sign byte when the top bit is set; JWK wants
        // the unsigned big-endian form.
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
            bytes = stripped;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static MockWebServer startStub() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/token")) {
                    lastTokenRequestBody = request.getBody().readUtf8();
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(tokenResponseJson);
                }
                if (path.startsWith("/certs")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(jwks());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("could not start the Google stub", e);
        }
        return server;
    }

    @AfterAll
    static void stopStub() throws Exception {
        GOOGLE.shutdown();
    }
}
