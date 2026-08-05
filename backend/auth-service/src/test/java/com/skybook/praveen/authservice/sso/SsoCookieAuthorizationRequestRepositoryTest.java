package com.skybook.praveen.authservice.sso;

import com.skybook.praveen.authservice.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.security.GeneralSecurityException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stateless pending-auth cookie (SSO_MODULE.md §3.3): what goes in must
 * come out ONLY under the server's key, bound to the state it was sealed
 * with. These tests drive the repository the way the OAuth2 filters do -
 * save on the start leg, load/remove on the callback leg - plus the ways an
 * attacker would drive it, plus the way PRODUCTION INFRASTRUCTURE broke it
 * once: on size.
 */
class SsoCookieAuthorizationRequestRepositoryTest {

    private static final String STATE = "opaque-state-value";

    private SsoCookieAuthorizationRequestRepository repository;

    @BeforeEach
    void buildRepository() throws GeneralSecurityException {
        JwtProperties properties = new JwtProperties();
        // The crypto derives its AES key from the PEM STRING - no RSA parsing
        // involved - so any stable text stands in for the key material here.
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----test-material-----END PRIVATE KEY-----");
        SsoStateCrypto crypto = new SsoStateCrypto(properties);

        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://skybook.example/api/auth/oauth2/callback/google")
                .scope(Set.of("openid", "email", "profile"))
                .authorizationUri("https://accounts.example/authorize")
                .tokenUri("https://accounts.example/token")
                .build();
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientRegistrationRepository> registrations = mock(ObjectProvider.class);
        when(registrations.getIfAvailable())
                .thenReturn(new InMemoryClientRegistrationRepository(google));

        repository = new SsoCookieAuthorizationRequestRepository(crypto, registrations, true);
    }

    private static OAuth2AuthorizationRequest authRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.example/authorize")
                .clientId("test-client")
                .redirectUri("https://skybook.example/api/auth/oauth2/callback/google")
                .scopes(Set.of("openid", "email", "profile"))
                .state(STATE)
                .attributes(attrs -> {
                    attrs.put("nonce", "the-raw-nonce");
                    attrs.put("code_verifier", "the-pkce-verifier");
                })
                .build();
    }

    /** The cookie the save leg wrote, replayed onto a fresh callback request. */
    private MockHttpServletRequest callbackRequestCarrying(MockHttpServletResponse saved, String state) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", state);
        String setCookie = saved.getHeader("Set-Cookie");
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        request.setCookies(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME, value));
        return request;
    }

    @Nested
    @DisplayName("the honest round trip")
    class RoundTrip {

        @Test
        void savesLoadsAndCarriesRememberAndReturnTo() {
            MockHttpServletRequest start = new MockHttpServletRequest();
            start.setParameter("remember", "true");
            start.setParameter("returnTo", "/trips");
            MockHttpServletResponse response = new MockHttpServletResponse();

            repository.saveAuthorizationRequest(authRequest(), start, response);

            String setCookie = response.getHeader("Set-Cookie");
            assertThat(setCookie)
                    .contains(SsoCookieAuthorizationRequestRepository.COOKIE_NAME + "=")
                    .contains("HttpOnly")
                    .contains("SameSite=Lax")
                    .contains("Path=/api/auth/oauth2/")
                    .contains("Max-Age=300");

            MockHttpServletRequest callback = callbackRequestCarrying(response, STATE);
            OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(callback);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getState()).isEqualTo(STATE);
            // The secrets came from the seal; the configuration came back from
            // the registration - together, everything post-callback Spring reads.
            assertThat(loaded.<String>getAttribute("nonce")).isEqualTo("the-raw-nonce");
            assertThat(loaded.<String>getAttribute("code_verifier")).isEqualTo("the-pkce-verifier");
            assertThat(loaded.getScopes()).contains("openid");
            assertThat(loaded.getRedirectUri())
                    .isEqualTo("https://skybook.example/api/auth/oauth2/callback/google");

            SsoPendingAuth pending = repository.readPending(callback).orElseThrow();
            assertThat(pending.remember()).isTrue();
            assertThat(pending.returnTo()).isEqualTo("/trips");
        }

        @Test
        void theSealedCookieStaysFarUnderTheLimitsThatBrokeItLive() {
            // Pinned because production found what MockMvc cannot: browsers cap
            // a cookie at 4096 bytes and nginx's default proxy_buffer_size is
            // 4-8 KB INCLUDING every other header. The first implementation
            // sealed the whole serialized OAuth2AuthorizationRequest (~4.5 KB)
            // and died at both walls; the payload is now three secrets and two
            // flags, and this test fails anyone who fattens it again.
            MockHttpServletRequest start = new MockHttpServletRequest();
            start.setParameter("returnTo", "/flights/search?from=LHR&to=DXB");
            MockHttpServletResponse response = new MockHttpServletResponse();

            repository.saveAuthorizationRequest(authRequest(), start, response);

            assertThat(response.getHeader("Set-Cookie").length()).isLessThan(1200);
        }

        @Test
        void removeReturnsTheRequestAndExpiresTheCookie() {
            MockHttpServletRequest start = new MockHttpServletRequest();
            MockHttpServletResponse saveResponse = new MockHttpServletResponse();
            repository.saveAuthorizationRequest(authRequest(), start, saveResponse);

            MockHttpServletRequest callback = callbackRequestCarrying(saveResponse, STATE);
            MockHttpServletResponse removeResponse = new MockHttpServletResponse();

            OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(callback, removeResponse);

            assertThat(removed).isNotNull();
            assertThat(removeResponse.getHeader("Set-Cookie")).contains("Max-Age=0");
        }
    }

    @Nested
    @DisplayName("the hostile shapes fail closed")
    class Hostile {

        @Test
        void aStateMismatchLoadsNothing() {
            // CSRF or a stale cookie - either way the echoed state does not
            // match the sealed one, and the flow must fail, not proceed.
            MockHttpServletRequest start = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            repository.saveAuthorizationRequest(authRequest(), start, response);

            MockHttpServletRequest callback = callbackRequestCarrying(response, "a-different-state");

            assertThat(repository.loadAuthorizationRequest(callback)).isNull();
        }

        @Test
        void aTamperedCookieIsIndistinguishableFromNoCookie() {
            MockHttpServletRequest start = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            repository.saveAuthorizationRequest(authRequest(), start, response);

            MockHttpServletRequest callback = callbackRequestCarrying(response, STATE);
            String honest = callback.getCookies()[0].getValue();
            // Flip one character of the sealed payload - GCM authentication
            // must reject it outright, never return a mangled object.
            char flipped = honest.charAt(10) == 'A' ? 'B' : 'A';
            callback.setCookies(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME,
                    honest.substring(0, 10) + flipped + honest.substring(11)));

            assertThat(repository.loadAuthorizationRequest(callback)).isNull();
            assertThat(repository.readPending(callback)).isEmpty();
        }

        @Test
        void garbageAndAbsenceAreTheSameEmptyAnswer() {
            MockHttpServletRequest bare = new MockHttpServletRequest();
            assertThat(repository.readPending(bare)).isEmpty();

            MockHttpServletRequest garbage = new MockHttpServletRequest();
            garbage.setCookies(new Cookie(SsoCookieAuthorizationRequestRepository.COOKIE_NAME, "not-base64!!"));
            assertThat(repository.readPending(garbage)).isEmpty();
        }

        @Test
        void aHostileReturnToIsSanitizedBeforeItIsEverSealed() {
            MockHttpServletRequest start = new MockHttpServletRequest();
            start.setParameter("returnTo", "//evil.com");
            MockHttpServletResponse response = new MockHttpServletResponse();
            repository.saveAuthorizationRequest(authRequest(), start, response);

            MockHttpServletRequest callback = callbackRequestCarrying(response, STATE);

            assertThat(repository.readPending(callback).orElseThrow().returnTo()).isEqualTo("/");
        }
    }
}
