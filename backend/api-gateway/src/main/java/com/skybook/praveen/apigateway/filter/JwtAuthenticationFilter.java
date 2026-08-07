package com.skybook.praveen.apigateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skybook.praveen.apigateway.security.HeaderAddingRequestWrapper;
import com.skybook.praveen.common.exception.ErrorResponse;
import com.skybook.praveen.security.AuthenticatedPrincipal;
import com.skybook.praveen.security.InvalidTokenException;
import com.skybook.praveen.security.JwtTokenValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The gateway's JWT enforcement point (SECURITY_HARDENING_MODULE.md §3.2). As
 * of the security-hardening branch it verifies with the shared
 * {@link JwtTokenValidator} - the exact logic every downstream service uses -
 * instead of a gateway-local validator, so the edge and the services can never
 * drift. The gateway's validator is configured with
 * {@code accept-service-tokens=false}, so a machine token can never enter
 * through the public edge (§5).
 *
 * Public routes (auth register/login, this gateway's own actuator) and CORS
 * preflight (OPTIONS) bypass validation. Everything else needs a valid
 * "Authorization: Bearer <token>"; on success the validated subject is attached
 * as X-Auth-User for logging/tracing (never trusted downstream as identity - §3.2).
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_HEADER = "X-Auth-User";

    /** Must match auth-service's SessionCookie.NAME. */
    public static final String SESSION_COOKIE = "skybook_session";

    /**
     * Must match booking-service's GuestSessionController names. Two, because
     * the {@code __Host-} prefix is only valid alongside {@code Secure}: on a
     * test fleet driven over plain HTTP the issuer drops both together, and a
     * gateway that knew only the prefixed name would ignore the very cookie
     * that fleet issues. The prefixed form is what every real environment
     * uses.
     */
    public static final String GUEST_COOKIE = "__Host-skybook_guest";
    public static final String GUEST_COOKIE_INSECURE = "skybook_guest";

    /**
     * The guest check-in page's declaration that THIS call is a guest errand
     * (see {@link #chooseAmbientCredential}). A header, not a cookie: the
     * browser sends cookies whether or not they are wanted, which is exactly
     * how a stale guest session came to answer for an account holder.
     */
    public static final String GUEST_INTENT_HEADER = "X-Skybook-Guest";

    /**
     * The guest-capable surface (GUEST_CHECKIN_MODULE.md §3.2): ON these
     * paths, and only these, the guest cookie is preferred over the account
     * session cookie when both are present. Everywhere else the guest cookie
     * is ignored entirely - a signed-in user who also looked up a booking as
     * a guest keeps their account everywhere except inside the guest
     * check-in surface itself. Explicit list, house doctrine.
     */
    private static final List<PathPattern> GUEST_CAPABLE_PATHS = List.of(
            new PathPatternParser().parse("/api/bookings/{id:\\d+}"),
            new PathPatternParser().parse("/api/bookings/reference/*"),
            new PathPatternParser().parse("/api/checkins/{id:\\d+}"),
            new PathPatternParser().parse("/api/checkins/booking/*"),
            new PathPatternParser().parse("/api/checkins/*/checkin"),
            new PathPatternParser().parse("/api/checkins/*/seat"),
            new PathPatternParser().parse("/api/boarding-passes/checkin/*"),
            new PathPatternParser().parse("/api/boarding-passes/checkin/*/email"),
            new PathPatternParser().parse("/api/baggage"),
            new PathPatternParser().parse("/api/baggage/checkin/*")
    );

    private static final List<PathPattern> PUBLIC_PATHS = List.of(
            new PathPatternParser().parse("/api/auth/register"),
            new PathPatternParser().parse("/api/auth/login"),
            // Clearing a cookie must work even with an already-expired token -
            // otherwise a user whose session lapsed could never sign out, and
            // the stale cookie would sit there producing 401s.
            new PathPatternParser().parse("/api/auth/logout"),
            // Password reset is pre-authentication by definition: the caller has
            // no session precisely because they cannot sign in.
            new PathPatternParser().parse("/api/auth/forgot-password"),
            new PathPatternParser().parse("/api/auth/reset-password"),
            // Guest-session issuance/end (GUEST_CHECKIN_MODULE.md §3): the
            // caller is here to GET a session, and ending one must work even
            // with the token inside the cookie already lapsed.
            new PathPatternParser().parse("/api/bookings/guest-session"),
            // "Sign in with Google" (SSO_MODULE.md §5): the start and callback
            // legs are pre-authentication by the same logic as password reset -
            // the caller is here to GET a session. providers is public shopping
            // data for the sign-in page. Exact paths, no wildcard, same doctrine
            // as the route table.
            new PathPatternParser().parse("/api/auth/oauth2/authorization/google"),
            new PathPatternParser().parse("/api/auth/oauth2/callback/google"),
            new PathPatternParser().parse("/api/auth/sso/providers"),
            // Flight schedules and fare quotes are public shopping data - a
            // visitor browses and prices trips before there is any account, the
            // way every travel site works. Only booking (seat hold onward) needs
            // a principal. flight-service still enforces ADMIN on writes and
            // booking-service still owns everything but /quote, so making these
            // tokenless at the gateway opens reads only; a write arrives with no
            // token and is rejected downstream.
            new PathPatternParser().parse("/api/flights/**"),
            new PathPatternParser().parse("/api/bookings/quote"),
            new PathPatternParser().parse("/api/bookings/fare-calendar"),
            // Actuator moved to the internal management port (§7); /livez + /readyz
            // are the k8s probe paths re-exposed on this main port.
            new PathPatternParser().parse("/actuator/**"),
            new PathPatternParser().parse("/livez"),
            new PathPatternParser().parse("/readyz")
    );

    private final JwtTokenValidator jwtValidator;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenValidator jwtValidator, ObjectMapper objectMapper) {
        this.jwtValidator = jwtValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (HttpMethod.OPTIONS.matches(request.getMethod()) || isPublic(path)) {
            // Public paths get no HeaderAddingRequestWrapper, so a
            // client-supplied X-Auth-User would flow downstream into logs and
            // traces. The gateway is that header's only legitimate author -
            // inbound copies are dropped (GUEST_CHECKIN_MODULE.md §2.9).
            filterChain.doFilter(
                    new com.skybook.praveen.apigateway.security.HeaderStrippingRequestWrapper(
                            request, AUTH_USER_HEADER),
                    response);
            return;
        }

        // THE GATEWAY IS THE SOLE TRANSLATION POINT between a browser
        // authentication credential and downstream bearer authentication.
        //
        // Deliberately phrased as "credential", not "the JWT cookie": today the
        // browser credential happens to be an httpOnly cookie carrying a signed
        // JWT, but it could become an opaque session id resolved against a
        // session store, an OIDC session, or something else again. Whatever it
        // becomes, only THIS method changes - downstream services keep receiving
        // an Authorization: Bearer header and keep validating RS256 locally, so
        // they stay stateless and unaware. That encapsulation is the point.
        //
        // Two credential forms are accepted, for two kinds of caller:
        //  - Authorization: Bearer ... - API clients (Postman, the e2e suite,
        //    scripts), which cannot use cookies conveniently;
        //  - the session cookie - browsers, where httpOnly keeps the credential
        //    out of reach of JavaScript, and therefore of XSS.
        // The header wins when both are present: an explicit credential should
        // never be silently overridden by an ambient one.
        boolean fromCookie = false;
        String authHeader = request.getHeader("Authorization");
        String token;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring("Bearer ".length());
        } else {
            // Two ambient credentials can coexist since guest check-in
            // (GUEST_CHECKIN_MODULE.md §3.2): the account session and the
            // booking-scoped guest cookie. Precedence is deterministic and
            // path-decided - the guest cookie wins ONLY inside the
            // guest-capable surface, the account session everywhere else -
            // so a signed-in user who looked up a booking as a guest is
            // never silently downgraded outside the check-in pages.
            String sessionToken = cookieValue(request, SESSION_COOKIE);
            String guestToken = cookieValue(request, GUEST_COOKIE);
            if (guestToken == null) {
                guestToken = cookieValue(request, GUEST_COOKIE_INSECURE);
            }
            token = chooseAmbientCredential(request, path, sessionToken, guestToken);
            fromCookie = token != null;
        }

        if (token == null || token.isBlank()) {
            reject(request, response, "Missing or malformed Authorization header");
            return;
        }

        try {
            AuthenticatedPrincipal principal = jwtValidator.validate(token);

            HttpServletRequest forwarded = wrapWithAuthUser(request, principal.subject());
            if (fromCookie) {
                // Translate the browser credential into the downstream form.
                // Services only ever read Authorization and re-validate the
                // token themselves rather than trusting the gateway (§3.2,
                // defence in depth) - so without this every browser-originated
                // request would 401 one hop later.
                forwarded = new HeaderAddingRequestWrapper(
                        forwarded, HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }

            filterChain.doFilter(forwarded, response);
        } catch (InvalidTokenException e) {
            log.warn("JWT rejected for {} {}: {}", request.getMethod(), path, e.getMessage());
            reject(request, response, "Invalid or expired token");
        }
    }

    /** The named cookie's value, or null when the request has none. */
    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean isPublic(String path) {
        PathContainer pathContainer = PathContainer.parsePath(path);
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pattern.matches(pathContainer));
    }

    private boolean isGuestCapable(String path) {
        PathContainer pathContainer = PathContainer.parsePath(path);
        return GUEST_CAPABLE_PATHS.stream().anyMatch(pattern -> pattern.matches(pathContainer));
    }

    /**
     * Which ambient credential speaks for this request when the browser
     * carries both an account session and a guest cookie.
     *
     * <p><b>The account session wins by default.</b> The first version of
     * this decided on PATH alone - guest cookie preferred anywhere in the
     * guest-capable surface - and that was wrong in the way only a real
     * browser shows you: a guest cookie outlives the errand that created
     * it, so an agency that had once checked a passenger in was still
     * carrying one, and every check-in and boarding-pass call on its OWN
     * bookings was answered with that stale booking-scoped credential.
     * The owner saw "not found" on their own booking, and the boarding
     * pass they had every right to see never appeared. Reported live.
     *
     * <p>So the guest credential is now used only when the caller ASKS for
     * it, by sending {@code X-Skybook-Guest: 1} - which the guest check-in
     * page does on every call and nothing else does. The cookie stays
     * httpOnly and unreadable to JavaScript; the page signals intent, not
     * the credential. When there is no session at all - a passenger with
     * no account, which is the whole point of the feature - the guest
     * cookie is used without ceremony.
     */
    private String chooseAmbientCredential(HttpServletRequest request, String path,
                                           String sessionToken, String guestToken) {
        if (guestToken == null || !isGuestCapable(path)) {
            return sessionToken;
        }
        if (sessionToken == null) {
            return guestToken;
        }
        return "1".equals(request.getHeader(GUEST_INTENT_HEADER)) ? guestToken : sessionToken;
    }

    private HttpServletRequest wrapWithAuthUser(HttpServletRequest request, String subject) {
        return new HeaderAddingRequestWrapper(request, AUTH_USER_HEADER, subject);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var errorBody = new ErrorResponse(
                LocalDateTime.now(), 401, "Unauthorized", message, request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }
}
