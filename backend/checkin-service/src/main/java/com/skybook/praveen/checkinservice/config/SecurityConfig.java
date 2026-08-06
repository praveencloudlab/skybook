package com.skybook.praveen.checkinservice.config;

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
 * Check-in authorization matrix (SECURITY_HARDENING_MODULE.md §4.4).
 *
 * <p>Back-office / gate operations are ADMIN at the URL level: manual manifest
 * creation ({@code POST /api/checkins}), window open, gate assignment, boarding,
 * flight-scoped listing, boarding-pass by-id + verify, and the manifest surface.
 *
 * <p>The passenger's own self-service surface (get own check-in, check-in,
 * change seat; own boarding pass + baggage) is authorized at the CONTROLLER via
 * {@link com.skybook.praveen.checkinservice.security.CheckInAccessGuard} against
 * the check-in's {@code ownerSubject} - the event-driven manifest flow calls the
 * service directly and must not be subject to the per-owner rule. Those URLs are
 * just {@code authenticated()} here; the object-level check runs in the handler.
 *
 * The shared JWT filter is built inside this chain, never a bean (§3.3).
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
                                                   JsonAccessDeniedHandler deniedHandler) throws Exception {

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

                        // Back-office / gate - ADMIN.
                        .requestMatchers(HttpMethod.POST, "/api/checkins").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/checkins/*/open", "/api/checkins/*/board", "/api/checkins/*/gate")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/checkins/flight/**").hasRole("ADMIN")
                        .requestMatchers("/api/manifests/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/api/boarding-passes/verify", "/api/boarding-passes/*").hasRole("ADMIN")

                        // The guest-permitted passenger surface, ENUMERATED
                        // (GUEST_CHECKIN_MODULE.md §2.3): exactly the check-in
                        // journey - state, check-in, seat, baggage, pass, email.
                        // Scope (WHICH booking) is enforced by the guard's
                        // requireBookingAccess.
                        .requestMatchers(HttpMethod.GET,
                                "/api/checkins/{id:\\d+}", "/api/checkins/booking/*",
                                "/api/boarding-passes/checkin/*", "/api/baggage/checkin/*")
                        .hasAnyRole("USER", "ADMIN", "GUEST")
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/checkins/*/checkin", "/api/checkins/*/seat")
                        .hasAnyRole("USER", "ADMIN", "GUEST")
                        .requestMatchers(HttpMethod.POST,
                                "/api/baggage", "/api/boarding-passes/checkin/*/email")
                        .hasAnyRole("USER", "ADMIN", "GUEST")

                        // The cage (GUEST_CHECKIN_MODULE.md §2.3): the chain no
                        // longer ends in authenticated(), which would have
                        // silently admitted guests to every FUTURE endpoint.
                        // Absence of a rule now means "no guests" - and, like
                        // booking-service, no ROLE_SERVICE either: checkin has
                        // no inbound service-to-service API (its integrations
                        // are Kafka), so a machine token here is defense in
                        // depth refusing an unused privilege.
                        .anyRequest().hasAnyRole("USER", "ADMIN")
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
