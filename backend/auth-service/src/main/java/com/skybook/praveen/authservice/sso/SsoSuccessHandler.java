package com.skybook.praveen.authservice.sso;

import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.security.SessionCookie;
import com.skybook.praveen.authservice.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The exchange point made concrete (SSO_MODULE.md §1): Google has
 * authenticated; SkyBook now authorizes. The OIDC principal is resolved to a
 * SkyBook account (§4.2) and exchanged for the SAME RS256 token and the SAME
 * session cookie every password login produces - from here on, no part of the
 * platform can tell how this session began.
 *
 * <p>Every outcome is a redirect, never a body: the caller is a browser
 * mid-navigation, halfway between Google and the SPA.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoSuccessHandler implements AuthenticationSuccessHandler {

    private final SsoAccountService accounts;
    private final JwtService jwtService;
    private final SessionCookie sessionCookie;
    private final SsoCookieAuthorizationRequestRepository pendingRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        // The carried values survive in the request's cookie header even
        // though the filter already expired the cookie on the response -
        // readPending is the second read of the same sealed payload.
        SsoPendingAuth pending = pendingRepository.readPending(request).orElse(null);
        boolean remember = pending != null && pending.remember();
        String returnTo = SafeReturnTo.sanitize(pending == null ? "/" : pending.returnTo());

        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        try {
            User user = accounts.resolve(
                    oidcUser.getSubject(),
                    oidcUser.getEmail(),
                    Boolean.TRUE.equals(oidcUser.getEmailVerified()),
                    oidcUser.getFullName());

            String token = jwtService.generateToken(user.getEmail(), user.getRole());
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie.issue(token, remember));
            response.sendRedirect(returnTo);
        } catch (SsoEmailUnverifiedException e) {
            log.info("Google sign-in rejected: unverified email");
            response.sendRedirect("/login?error=sso_email_unverified");
        }
    }
}
