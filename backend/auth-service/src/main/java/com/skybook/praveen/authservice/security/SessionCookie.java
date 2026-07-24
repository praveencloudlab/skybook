package com.skybook.praveen.authservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The browser session cookie (FRONTEND_MODULE.md §10.1, revised).
 *
 * <p>The access token is ALSO returned in the login response body, which is what
 * API clients (Postman, the e2e suite, scripts) use. This cookie exists purely
 * for the browser, where storing a bearer token in JS-readable storage means any
 * XSS - including one in a transitive dependency - can exfiltrate it and replay
 * it elsewhere for the remainder of its 60-minute life, with no revocation to
 * stop it.
 *
 * <p><b>httpOnly does not prevent XSS</b>, and it is worth being precise about
 * what it buys: injected script can still call the API from the victim's browser
 * while the page is open. What it prevents is the token being READ and taken
 * away for use elsewhere. Given the token is unrevocable, that distinction
 * matters.
 *
 * <p>{@code SameSite=Lax} is the CSRF control, and it only works because the SPA
 * is served same-origin with the API (Vite proxy in dev, nginx in the container).
 * A cross-origin SPA would have forced {@code SameSite=None}, which is sent on
 * cross-site requests and would have needed separate CSRF tokens.
 */
@Component
public class SessionCookie {

    public static final String NAME = "skybook_session";

    private final Duration maxAge;
    private final boolean secure;

    public SessionCookie(
            @Value("${jwt.expiration}") long expirationMs,
            // Defaults to true: an insecure default is exactly what §10 of the
            // security module set out to remove. Browsers treat http://localhost
            // as a secure context, so Secure cookies work in local development
            // without weakening anything.
            @Value("${jwt.session-cookie.secure:true}") boolean secure) {
        // Match the token's own lifetime: a cookie outliving the token it holds
        // just produces confusing 401s on a session the browser thinks is live.
        this.maxAge = Duration.ofMillis(expirationMs);
        this.secure = secure;
    }

    /** A cookie carrying the freshly issued token. */
    public String issue(String token) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString();
    }

    /**
     * A cookie that immediately expires the session.
     *
     * <p>Required, not optional: an httpOnly cookie is invisible to JavaScript,
     * so the browser CANNOT sign itself out. Without a server endpoint there is
     * no way to end a session at all.
     */
    public String expire() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build()
                .toString();
    }

    public static HttpHeaders headerWith(String cookieValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookieValue);
        return headers;
    }
}
