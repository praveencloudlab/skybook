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

    private static final List<PathPattern> PUBLIC_PATHS = List.of(
            new PathPatternParser().parse("/api/auth/register"),
            new PathPatternParser().parse("/api/auth/login"),
            // Clearing a cookie must work even with an already-expired token -
            // otherwise a user whose session lapsed could never sign out, and
            // the stale cookie would sit there producing 401s.
            new PathPatternParser().parse("/api/auth/logout"),
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
            filterChain.doFilter(request, response);
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
            token = sessionCookieValue(request);
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

    /** The browser session cookie, or null when the request has none. */
    private String sessionCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean isPublic(String path) {
        PathContainer pathContainer = PathContainer.parsePath(path);
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pattern.matches(pathContainer));
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
