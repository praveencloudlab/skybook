package com.skybook.praveen.authservice.config;

import com.skybook.praveen.authservice.security.JwtAuthenticationFilter;
import com.skybook.praveen.authservice.security.ServiceClientDetailsService;
import com.skybook.praveen.authservice.sso.SsoCookieAuthorizationRequestRepository;
import com.skybook.praveen.authservice.sso.SsoFailureHandler;
import com.skybook.praveen.authservice.sso.SsoSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two ordered filter chains (SECURITY_HARDENING_MODULE.md §3.3, review 3):
 *
 * <ol>
 *   <li><b>@Order(1) — client-credential chain</b> for
 *       {@code /api/auth/service-token}: HTTP Basic against the
 *       service-client registry (BCrypt), no JWT. A machine caller obtaining
 *       its first {@code ROLE_SERVICE} token cannot already present one, so
 *       this endpoint must not sit behind the JWT filter.</li>
 *   <li><b>@Order(2) — application chain</b>: register/login public, everything
 *       else needs a valid RS256 user token via the JWT filter.</li>
 * </ol>
 *
 * Spring Security runs only the first chain whose {@code securityMatcher}
 * matches, so ordering + matcher keep the two authentication styles cleanly
 * separated.
 */
@Configuration
public class SecurityConfig {

    /**
     * The JWT filter is a {@code @Component OncePerRequestFilter}, which Spring
     * Boot would otherwise auto-register in the servlet container so it runs on
     * EVERY request - including the {@code @Order(1)} client-credential chain
     * (SECURITY_HARDENING_MODULE.md §3.3, review 4). Disabling the container
     * registration confines it to the application chain, where it is added
     * explicitly below.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(1)
    // Sonar S4502 - CSRF disabled, reviewed and accepted. This chain is the
    // machine-to-machine token endpoint: HTTP Basic, stateless, no cookie and
    // no session. A browser never holds credentials for it, so there is no
    // ambient authority for a cross-site request to ride on.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain serviceTokenFilterChain(HttpSecurity http,
                                                       ServiceClientDetailsService clientDetailsService,
                                                       PasswordEncoder passwordEncoder) throws Exception {

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(clientDetailsService);
        provider.setPasswordEncoder(passwordEncoder);

        // Authentication failures on this Basic-auth endpoint must return 401,
        // not 403 (SECURITY_HARDENING_MODULE.md §6). Without an explicit entry
        // point, an anonymous request tripping .authenticated() surfaces as an
        // access-denied 403; a bad/unknown/missing credential all now return an
        // identical, indistinguishable 401 (no client enumeration, §3.3).
        AuthenticationEntryPoint entryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

        http
                // guest-token joins the same internal-only chain
                // (GUEST_CHECKIN_MODULE.md §3.1): both endpoints authenticate a
                // machine client by HTTP Basic, and neither is routed from the
                // public edge.
                .securityMatcher("/api/auth/service-token", "/api/auth/guest-token")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .authenticationProvider(provider)
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));

        return http.build();
    }

    @Bean
    @Order(2)
    // Sonar S4502 - CSRF disabled, reviewed and accepted.
    //
    // This chain authenticates from the Authorization: Bearer header only - it
    // ISSUES the skybook_session cookie but never consumes one. The cookie is
    // consumed one hop up at the API gateway, which validates it and injects
    // the Bearer header, so the CSRF boundary is the gateway. The control is
    // SameSite=Lax on a same-origin SPA (see SessionCookie): withheld from
    // cross-site POST/PUT/PATCH/DELETE and from cross-site fetch/XHR. Lax does
    // travel on top-level cross-site GET, so the residual risk is a
    // state-changing GET - all 48 GET endpoints were audited and none mutates.
    //
    // Known and accepted: login CSRF (forcing a victim into an attacker session)
    // is not covered by SameSite, since it needs no pre-existing cookie. It is
    // tolerated here because nothing of the victim is exposed by it. The OIDC
    // callback (SSO_MODULE.md §7) inherits exactly this posture - it too only
    // signs the caller IN - and its state parameter is bound to the encrypted
    // pending-auth cookie besides.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain applicationFilterChain(HttpSecurity http,
                                                      JwtAuthenticationFilter jwtAuthenticationFilter,
                                                      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                                      ObjectProvider<OAuth2AuthorizationRequestResolver> authorizationRequestResolver,
                                                      SsoCookieAuthorizationRequestRepository pendingAuthRepository,
                                                      SsoSuccessHandler ssoSuccessHandler,
                                                      SsoFailureHandler ssoFailureHandler) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                // Now stated, not just true by accident (SSO_MODULE.md §2.5):
                // this service holds no HTTP session, and the OIDC flow keeps
                // its in-flight state in an encrypted cookie precisely so that
                // stays the case.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // logout is public on purpose: it only expires a cookie, and
                        // a user whose token has already lapsed must still be able
                        // to sign out rather than being stuck with a stale cookie.
                        // (/me is deliberately NOT here - it must require a valid
                        // token, since answering "who are you" to an anonymous
                        // caller is the whole thing it must not do.)
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout")
                        .permitAll()
                        // Password reset is pre-authentication by definition: the
                        // caller cannot sign in, which is the whole reason they are
                        // here. forgot-password never confirms whether an account
                        // exists; reset-password succeeds only against a valid token.
                        .requestMatchers("/api/auth/forgot-password", "/api/auth/reset-password")
                        .permitAll()
                        // Email verification is pre-authentication by the same
                        // logic: the caller cannot sign in BECAUSE they are not
                        // verified yet. verify-email succeeds only against the
                        // emailed code; resend-verification never confirms
                        // whether an account exists.
                        .requestMatchers("/api/auth/verify-email", "/api/auth/resend-verification")
                        .permitAll()
                        // SSO surface (SSO_MODULE.md §5): permitted STATICALLY,
                        // enabled or not, so both worlds present one security
                        // surface. When enabled, oauth2Login owns start+callback;
                        // when disabled, SsoDisabledController answers the same
                        // paths with a human-readable redirect.
                        .requestMatchers("/api/auth/oauth2/authorization/google",
                                "/api/auth/oauth2/callback/google",
                                "/api/auth/sso/providers")
                        .permitAll()
                        .requestMatchers("/actuator/**", "/livez", "/readyz").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // oauth2Login iff the Google registration exists (SsoGoogleConfig's
        // condition) - feature-off must mean "these filters are not in the
        // chain", not "these filters fail fast".
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        if (registrations != null) {
            http.oauth2Login(oauth -> oauth
                    .clientRegistrationRepository(registrations)
                    .authorizationEndpoint(endpoint -> endpoint
                            .baseUri("/api/auth/oauth2/authorization")
                            .authorizationRequestResolver(authorizationRequestResolver.getObject())
                            .authorizationRequestRepository(pendingAuthRepository))
                    .redirectionEndpoint(endpoint -> endpoint
                            .baseUri("/api/auth/oauth2/callback/*"))
                    .successHandler(ssoSuccessHandler)
                    .failureHandler(ssoFailureHandler));
        }

        return http.build();
    }
}
