package com.skybook.praveen.paymentservice.config;

import com.skybook.praveen.security.JsonAccessDeniedHandler;
import com.skybook.praveen.security.JsonAuthenticationEntryPoint;
import com.skybook.praveen.security.JwtAuthenticationFilter;
import com.skybook.praveen.security.JwtTokenValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Payment-service authorization (SECURITY_HARDENING_MODULE.md §4.4). URL-level
 * rules cover the role-only surfaces; per-object OWNER checks live in the
 * controllers (HTTP boundary) so the event-driven payment lifecycle - which
 * calls the same service/facade methods on a Kafka thread with no
 * SecurityContext - is never subject to them.
 *
 * <ul>
 *   <li>manual create ({@code POST /api/payments}), cancel, refund, and the raw
 *       refund listing → <b>ADMIN</b></li>
 *   <li>everything else authenticated; the reads + authorize/capture add an
 *       OWNER-or-ADMIN check in the controller</li>
 * </ul>
 *
 * The shared JWT filter is built here (not a bean) so it runs only in this chain.
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenValidator validator,
                                                   JsonAuthenticationEntryPoint entryPoint,
                                                   JsonAccessDeniedHandler accessDeniedHandler) throws Exception {

        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(validator, entryPoint);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // All of actuator scraped tokenless by Prometheus over the
                        // internal network (§7); step 10 isolates the management port.
                        .requestMatchers("/actuator/**", "/livez", "/readyz").permitAll()
                        // ADMIN-only: manual create, cancel, refund, raw refund listing.
                        .requestMatchers(HttpMethod.POST, "/api/payments").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/payments/*/cancel", "/api/payments/*/refund").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/refunds").hasRole("ADMIN")
                        // Everything else needs a token; the controller adds the OWNER check.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
