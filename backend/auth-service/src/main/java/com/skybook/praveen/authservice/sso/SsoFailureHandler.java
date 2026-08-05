package com.skybook.praveen.authservice.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Failure mapping (SSO_MODULE.md §7): the user's own cancellation gets its own
 * message; every technical failure - state mismatch, expired pending cookie,
 * token-endpoint error, JWKS failure, invalid ID token - collapses into ONE
 * generic bucket. The distinctions are logged for operators; a passenger can
 * act on none of them, and enumerating internals in a URL parameter is attack
 * surface for no gain.
 */
@Slf4j
@Component
public class SsoFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {

        String code = "sso_failed";
        if (exception instanceof OAuth2AuthenticationException oauth2
                && "access_denied".equals(oauth2.getError().getErrorCode())) {
            // The user hit Cancel/Back at the consent screen - not a failure of
            // anything, and the copy should not read like one.
            code = "sso_cancelled";
        }

        log.warn("Google sign-in failed ({}): {}", code, exception.getMessage());
        response.sendRedirect("/login?error=" + code);
    }
}
