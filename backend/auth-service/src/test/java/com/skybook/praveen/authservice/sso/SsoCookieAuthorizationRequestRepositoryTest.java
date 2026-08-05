package com.skybook.praveen.authservice.sso;

import com.skybook.praveen.authservice.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stateless pending-auth cookie (SSO_MODULE.md §3.3): what goes in must
 * come out ONLY under the server's key, bound to the state it was sealed
 * with. These tests drive the repository the way the OAuth2 filters do -
 * save on the start leg, load/remove on the callback leg - plus the ways an
 * attacker would drive it.
 */
class SsoCookieAuthorizationRequestRepositoryTest {

    private static final String STATE = "opaque-state-value";

    private SsoCookieAuthorizationRequestRepository repository;
    private SsoStateCrypto crypto;

    @BeforeEach
    void buildRepository() throws GeneralSecurityException {
        JwtProperties properties = new JwtProperties();
        // The crypto derives its AES key from the PEM STRING - no RSA parsing
        // involved - so any stable text stands in for the key material here.
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----test-material-----END PRIVATE KEY-----");
        crypto = new SsoStateCrypto(properties);
        repository = new SsoCookieAuthorizationRequestRepository(crypto, true);
    }

    private static OAuth2AuthorizationRequest authRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.example/authorize")
                .clientId("test-client")
                .redirectUri("https://skybook.example/api/auth/oauth2/callback/google")
                .scopes(java.util.Set.of("openid", "email", "profile"))
                .state(STATE)
                .attributes(attrs -> attrs.put("nonce", "the-nonce"))
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
            assertThat(loaded.<String>getAttribute("nonce")).isEqualTo("the-nonce");

            SsoPendingAuth pending = repository.readPending(callback).orElseThrow();
            assertThat(pending.remember()).isTrue();
            assertThat(pending.returnTo()).isEqualTo("/trips");
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
