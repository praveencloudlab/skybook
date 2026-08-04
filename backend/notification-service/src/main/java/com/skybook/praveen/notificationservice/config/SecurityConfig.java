package com.skybook.praveen.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Notification-service security (SECURITY_HARDENING_MODULE.md §3.2, §4.4).
 *
 * <p>Notification is a pure Kafka email consumer - it has NO business HTTP API,
 * so there is no authorization matrix to enforce. Its only HTTP surface is
 * actuator (Prometheus scrapes it over the internal network; step 10 moves the
 * management surface to an internal-only port). This chain therefore permits
 * actuator + CORS preflight and <b>denies everything else by default</b>, so a
 * controller added here later is locked down rather than accidentally open -
 * defense in depth without a JWT filter it has no tokens to validate.
 */
@Configuration
public class SecurityConfig {

    @Bean
    // Sonar S4502 - CSRF disabled here is reviewed and accepted, not overlooked.
    //
    // The browser ambient credential is the skybook_session cookie. It is
    // consumed at the API GATEWAY, which validates it and injects an
    // Authorization: Bearer header before forwarding - so the CSRF boundary
    // is the gateway, not this service. The control there is SameSite=Lax on
    // a same-origin SPA (auth-service SessionCookie documents why), which
    // withholds the cookie from cross-site POST/PUT/PATCH/DELETE and from
    // cross-site fetch/XHR entirely. Lax still travels on top-level cross-site
    // GET, so the residual risk is a state-changing GET - all 48 GET endpoints
    // were audited and none mutates state.
    //
    // A CSRF token would protect nothing extra and would break the non-browser
    // callers (e2e suite, Postman, service-to-service Basic auth). Revisit if
    // the SPA moves cross-origin, the cookie moves to SameSite=None, or a GET
    // is ever given a side effect.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Actuator stays scrapeable over the internal network
                        // (isolated to an internal management port in step 10).
                        .requestMatchers("/actuator/**", "/livez", "/readyz").permitAll()
                        // No business endpoints exist; deny by default.
                        .anyRequest().denyAll()
                );

        return http.build();
    }
}
