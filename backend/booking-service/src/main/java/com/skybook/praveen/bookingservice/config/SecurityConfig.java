package com.skybook.praveen.bookingservice.config;

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
 * Booking-service security (SECURITY_HARDENING_MODULE.md §13 step 4):
 * AUTHENTICATION-ONLY rollout. The shared JWT filter validates any present
 * token and populates the SecurityContext, but the full authorization matrix
 * (§4.4) is not switched on yet - that is step 6. The one exception is
 * <b>booking creation</b>, made {@code authenticated()} now so a real principal
 * is always present when {@code ownerSubject} is captured (§4.2), closing the
 * "new booking with a null owner" gap before enforcement flips fleet-wide.
 *
 * The shared {@code JwtAuthenticationFilter} is built here (not a bean) so it
 * only runs inside this chain - never auto-registered onto another path (§3.3).
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

                        // Fare quote is public shopping data - a visitor prices a
                        // trip before any account exists. It reads nothing owned
                        // and creates nothing; the booking it might lead to still
                        // needs a principal below.
                        .requestMatchers(HttpMethod.POST, "/api/bookings/quote").permitAll()
                        // The fare calendar is the same public shopping data,
                        // date-by-date - it reads nothing owned.
                        .requestMatchers(HttpMethod.GET, "/api/bookings/fare-calendar").permitAll()

                        // Back-office - ADMIN. list-all + search + confirm + complete.
                        .requestMatchers(HttpMethod.GET, "/api/bookings").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/bookings/search").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/bookings/*/confirm", "/api/bookings/*/complete")
                        .hasRole("ADMIN")

                        // Everything else (create, quote, own get/reference/cancel,
                        // passenger check-in/board) - a USER or ADMIN token; OWNER
                        // enforced in the controller via BookingAccessGuard. Booking
                        // has NO inbound service-to-service API, so a ROLE_SERVICE
                        // token is rejected here rather than being treated as
                        // owner-privileged by SecurityAccess (defense in depth).
                        .anyRequest().hasAnyRole("USER", "ADMIN")
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
