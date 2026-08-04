package com.skybook.praveen.inventoryservice.config;

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
 * Inventory-service authorization matrix (SECURITY_HARDENING_MODULE.md §4.4).
 * Reads of reference data are open to any authenticated caller; reference-data
 * writes (aircraft, seat maps, inventory creation, close/reopen, seat status)
 * are ADMIN; the seat operations booking- and check-in-service call
 * (hold/auto-hold/release/reserve/cancel) are ADMIN or SERVICE.
 *
 * The shared JWT filter is built inside this chain, never a bean, so it can't
 * be auto-registered onto another path (§3.3).
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

                        // Cabin availability feeds the now-public fare quote: a
                        // visitor prices a trip before any account exists, so
                        // booking-service reads this without a caller token. It
                        // is public shopping data, exactly like the flight
                        // schedule - and inventory stays internal-only (the
                        // gateway never routes it tokenless from outside), so
                        // this opens one read on the internal network, nothing more.
                        .requestMatchers(HttpMethod.GET, "/api/inventory/flights/*/cabins").permitAll()

                        // Seat operations - the internal service→service surface.
                        .requestMatchers(HttpMethod.POST,
                                "/api/inventory/hold", "/api/inventory/release",
                                "/api/inventory/flights/*/holds/auto",
                                "/api/reservations", "/api/reservations/cancel")
                        .hasAnyRole("ADMIN", "SERVICE")

                        // Reference-data creation - ADMIN.
                        .requestMatchers(HttpMethod.POST,
                                "/api/inventory",
                                "/api/aircraft", "/api/aircraft/*/seats", "/api/aircraft/*/seat-map")
                        .hasRole("ADMIN")
                        // Aircraft/seat status changes + inventory close/reopen - ADMIN.
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("ADMIN")

                        // Everything else (all GETs, POST /search) - any authenticated caller.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
