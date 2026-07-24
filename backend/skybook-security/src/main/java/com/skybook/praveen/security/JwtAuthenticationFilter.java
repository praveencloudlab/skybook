package com.skybook.praveen.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Populates the Spring {@code SecurityContext} from a validated bearer token
 * (SECURITY_HARDENING_MODULE.md §3.2). It only ever ESTABLISHES identity;
 * authorization (roles, ownership) is enforced by each service's own
 * SecurityConfig on top of the authentication this filter provides.
 *
 * Behaviour, per the rollout flag (§3.2, §13 step 4):
 * <ul>
 *   <li>valid bearer token → validate, set the authentication</li>
 *   <li>absent token → left unauthenticated; the chain decides (permitAll vs.
 *       authenticated()). While {@code enforcementEnabled=false} this is how
 *       an in-progress rollout stays non-breaking.</li>
 *   <li>present but INVALID token → rejected 401 immediately, in both modes -
 *       a bad token is never treated as anonymous.</li>
 * </ul>
 *
 * CORS preflight (OPTIONS) is skipped unconditionally - browsers never attach
 * an Authorization header to a preflight (same rule the gateway already uses).
 * The filter is not a Spring bean here (see JwtSecurityAutoConfiguration): it
 * is instantiated only inside the intended security chain so it can never be
 * auto-registered by the servlet container onto an unintended path.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenValidator validator;
    private final JsonAuthenticationEntryPoint entryPoint;

    public JwtAuthenticationFilter(JwtTokenValidator validator, JsonAuthenticationEntryPoint entryPoint) {
        this.validator = validator;
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // No credential presented - stay anonymous and let the authorization
            // rules decide. Never invent an identity.
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        try {
            AuthenticatedPrincipal principal = validator.validate(token);
            SecurityContextHolder.getContext().setAuthentication(toAuthentication(principal));
        } catch (InvalidTokenException e) {
            log.warn("JWT rejected for {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response,
                    new org.springframework.security.authentication.BadCredentialsException("invalid token"));
            return;
        }

        chain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken toAuthentication(AuthenticatedPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        return authentication;
    }
}
