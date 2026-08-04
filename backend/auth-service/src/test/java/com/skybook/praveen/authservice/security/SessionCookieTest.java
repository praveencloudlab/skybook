package com.skybook.praveen.authservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.HttpCookie;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser session cookie (FRONTEND_MODULE.md §10.1). Every attribute here is
 * load-bearing: httpOnly is what stops an XSS from carrying the token away,
 * SameSite=Lax is the CSRF control, and the Max-Age/no-Max-Age split is the
 * whole difference between "keep me signed in" and a cookie the browser drops
 * when the window closes.
 */
class SessionCookieTest {

    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final String TOKEN = "a.jwt.token";

    private static final SessionCookie SECURE_COOKIE = new SessionCookie(ONE_HOUR_MS, true);

    private static HttpCookie parse(String setCookieHeader) {
        List<HttpCookie> cookies = HttpCookie.parse(setCookieHeader);
        assertThat(cookies).hasSize(1);
        return cookies.get(0);
    }

    @Nested
    @DisplayName("issue puts the token where JavaScript cannot reach it")
    class Issue {

        @Test
        void marksTheCookieHttpOnlySecureAndSameSiteLax() {
            String header = SECURE_COOKIE.issue(TOKEN, true);

            HttpCookie cookie = parse(header);
            assertThat(cookie.getName()).isEqualTo(SessionCookie.NAME);
            assertThat(cookie.getValue()).isEqualTo(TOKEN);
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getSecure()).isTrue();
            assertThat(cookie.getPath()).isEqualTo("/");
            // java.net.HttpCookie predates SameSite and drops the attribute, so
            // the CSRF control has to be asserted on the header itself.
            assertThat(header).contains("SameSite=Lax");
        }

        @Test
        void carriesAMaxAgeMatchingTheTokenLifetimeWhenKeepMeSignedInIsChosen() {
            String header = SECURE_COOKIE.issue(TOKEN, true);

            // A cookie outliving its token would just produce 401s on a session
            // the browser still believes is live.
            assertThat(parse(header).getMaxAge()).isEqualTo(ONE_HOUR_MS / 1000);
            assertThat(header).contains("Max-Age=3600");
        }

        @Test
        void omitsMaxAgeEntirelyWhenKeepMeSignedInIsNotChosen() {
            String header = SECURE_COOKIE.issue(TOKEN, false);

            // No Max-Age and no Expires makes it a session cookie: the browser
            // discards it on close, which is the safer default on a shared machine.
            assertThat(header).doesNotContain("Max-Age");
            assertThat(header).doesNotContain("Expires");
            assertThat(parse(header).getMaxAge()).isNegative();
        }

        @Test
        void keepsEveryProtectiveAttributeOnTheNonPersistentShapeToo() {
            String header = SECURE_COOKIE.issue(TOKEN, false);

            HttpCookie cookie = parse(header);
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getSecure()).isTrue();
            assertThat(header).contains("SameSite=Lax");
        }

        @Test
        void dropsTheSecureFlagOnlyWhenADeploymentExplicitlyTurnsItOff() {
            // The property defaults to true; this is the opt-out an operator has
            // to make deliberately, and it must not silently weaken anything else.
            String header = new SessionCookie(ONE_HOUR_MS, false).issue(TOKEN, true);

            assertThat(parse(header).getSecure()).isFalse();
            assertThat(parse(header).isHttpOnly()).isTrue();
            assertThat(header).contains("SameSite=Lax");
        }
    }

    @Nested
    @DisplayName("expire is the only way a browser can sign itself out")
    class Expire {

        @Test
        void clearsTheValueAndExpiresTheCookieImmediately() {
            String header = SECURE_COOKIE.expire();

            HttpCookie cookie = parse(header);
            assertThat(cookie.getName()).isEqualTo(SessionCookie.NAME);
            assertThat(cookie.getValue()).isEmpty();
            assertThat(cookie.getMaxAge()).isZero();
            assertThat(cookie.getPath()).isEqualTo("/");
        }

        @Test
        void keepsTheSameAttributesSoTheBrowserOverwritesTheOriginalCookie() {
            // A clearing cookie that differs in path/secure/httpOnly would be
            // stored alongside the live one instead of replacing it.
            String header = SECURE_COOKIE.expire();

            assertThat(parse(header).isHttpOnly()).isTrue();
            assertThat(parse(header).getSecure()).isTrue();
            assertThat(header).contains("SameSite=Lax");
        }
    }

    @Nested
    @DisplayName("headerWith")
    class HeaderHelper {

        @Test
        void putsTheCookieOnTheSetCookieResponseHeader() {
            HttpHeaders headers = SessionCookie.headerWith(SECURE_COOKIE.issue(TOKEN, true));

            assertThat(headers.get(HttpHeaders.SET_COOKIE))
                    .singleElement().asString().contains(SessionCookie.NAME + "=" + TOKEN);
        }
    }
}
