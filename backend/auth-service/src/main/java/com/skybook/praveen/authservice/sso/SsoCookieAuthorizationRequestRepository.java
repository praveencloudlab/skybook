package com.skybook.praveen.authservice.sso;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The stateless replacement for Spring's session-backed authorization-request
 * storage (SSO_MODULE.md §2.5/§3.3). Auth-service holds no HTTP session -
 * that is a property worth keeping, not an accident - so the in-flight
 * secrets (state, nonce, PKCE verifier) plus SkyBook's carried values
 * (remember, returnTo) ride in ONE encrypted, five-minute, httpOnly cookie
 * scoped to the /api/auth/oauth2/ subtree.
 *
 * <p><b>The cookie carries secrets, not configuration.</b> At save time the
 * per-flow values are extracted from Spring's request object; at load time
 * the request object is REBUILT around them from the Google registration.
 * The first live deployment is why: sealing the whole serialized
 * {@code OAuth2AuthorizationRequest} produced a ~4.5 KB cookie - over
 * nginx's default proxy_buffer_size (502 at the frontend's /api hop) and
 * over the browser's 4096-byte per-cookie cap (silent drop). Both limits
 * live OUTSIDE the JVM, which is how they escaped a MockMvc flow test; the
 * repository test now pins the sealed size instead.
 *
 * <p>SameSite=Lax is sufficient because the callback is a top-level GET
 * navigation (Google redirecting the browser back), on which Lax cookies are
 * sent - the same property the skybook_session cookie already relies on.
 *
 * <p>{@link #removeAuthorizationRequest} expires the cookie on the RESPONSE;
 * the request object still carries the original header, which is what lets
 * the success/failure handlers call {@link #readPending} afterwards - one
 * read for Spring's state machine, one for SkyBook's carried values, same
 * sealed payload.
 */
@Component
public class SsoCookieAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String COOKIE_NAME = "skybook_sso_pending";

    /** The consent screen's lifetime, not a session (SSO_MODULE.md §3.3). */
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String COOKIE_PATH = "/api/auth/oauth2/";
    /** Spring keeps the PKCE verifier under this attribute post-redirect. */
    private static final String CODE_VERIFIER_ATTR = "code_verifier";
    private static final String NONCE_ATTR = "nonce";
    private static final String REGISTRATION_ID = "google";

    private final SsoStateCrypto crypto;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final boolean secure;

    public SsoCookieAuthorizationRequestRepository(
            SsoStateCrypto crypto,
            // ObjectProvider because the registration only exists when SSO is
            // enabled - and this repository is only ever DRIVEN when it is.
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            // The same flag SessionCookie uses: secure-by-default, and browsers
            // treat http://localhost as a secure context, so local dev works.
            @Value("${jwt.session-cookie.secure:true}") boolean secure) {
        this.crypto = crypto;
        this.clientRegistrations = clientRegistrations;
        this.secure = secure;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        return readPending(request)
                // The state echo must match the sealed one - a mismatch is
                // either CSRF or a stale cookie, and both must fail closed.
                .filter(pending -> pending.state() != null && pending.state().equals(state))
                .map(this::rebuild)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorizationRequest == null) {
            expireCookie(response);
            return;
        }
        // The start request is where remember/returnTo arrive as query params
        // (SSO_MODULE.md §3.2); sanitize returnTo at WRITE time as well as read
        // time, so a hostile value never even gets sealed.
        boolean remember = Boolean.parseBoolean(request.getParameter("remember"));
        String returnTo = SafeReturnTo.sanitize(request.getParameter("returnTo"));

        String sealed = crypto.seal(new SsoPendingAuth(
                authorizationRequest.getState(),
                authorizationRequest.getAttribute(NONCE_ATTR),
                authorizationRequest.getAttribute(CODE_VERIFIER_ATTR),
                remember,
                returnTo));
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, sealed)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(TTL)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        expireCookie(response);
        return authorizationRequest;
    }

    /** The full sealed payload - the handlers' view (remember + returnTo included). */
    public Optional<SsoPendingAuth> readPending(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return crypto.unseal(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Configuration back around the secrets: everything Spring's post-callback
     * machinery reads from this object - registration id for the login filter,
     * scopes for the OIDC check, redirect URI for the token request, nonce and
     * verifier from the attributes - comes from the registration or the sealed
     * triple. Null when SSO is off, which downstream treats as "no request".
     */
    private OAuth2AuthorizationRequest rebuild(SsoPendingAuth pending) {
        ClientRegistrationRepository repository = clientRegistrations.getIfAvailable();
        if (repository == null) {
            return null;
        }
        ClientRegistration registration = repository.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            return null;
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(OAuth2ParameterNames.REGISTRATION_ID, registration.getRegistrationId());
        if (pending.nonce() != null) {
            attributes.put(NONCE_ATTR, pending.nonce());
        }
        if (pending.codeVerifier() != null) {
            attributes.put(CODE_VERIFIER_ATTR, pending.codeVerifier());
        }

        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(pending.state())
                .attributes(attrs -> attrs.putAll(attributes))
                .build();
    }

    private void expireCookie(HttpServletResponse response) {
        ResponseCookie expired = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
    }
}
