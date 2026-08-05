package com.skybook.praveen.authservice.sso;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Owns the SSO endpoints when the feature is OFF (SSO_MODULE.md §5). The gate
 * list - gateway routes, PUBLIC_PATHS, permitAll - is static and identical in
 * both worlds; only the owner of the paths changes. Without this controller a
 * disabled-mode click would fall through to the JWT filter and surface as a
 * raw JSON 401 to a browser mid-navigation; with it, the browser lands back
 * on the sign-in page with copy a person can read.
 */
@RestController
@ConditionalOnExpression("!T(org.springframework.util.StringUtils).hasText('${skybook.sso.google.client-id:}')")
public class SsoDisabledController {

    @GetMapping({"/api/auth/oauth2/authorization/google", "/api/auth/oauth2/callback/google"})
    public void unavailable(HttpServletResponse response) throws IOException {
        response.sendRedirect("/login?error=sso_unavailable");
    }
}
