package com.skybook.praveen.flightservice.config;

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
 * Flight authorization matrix (SECURITY_HARDENING_MODULE.md §4.4). Flight and
 * schedule data are reference data: any authenticated caller may read them
 * (booking- and inventory-service do so with the propagated user/ADMIN token);
 * every create/update/cancel/delete is ADMIN.
 *
 * Flipped LAST in step 6 (§13) - only after every service that calls
 * flight-service (booking, inventory) already sends a valid token, so enabling
 * enforcement here can't 401 an internal call.
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
                        // All of actuator (health,info,metrics,prometheus,circuitbreakers)
                        // is scraped tokenless by Prometheus over the internal network (§7);
                        // step 10 isolates it to an internal-only management port.
                        .requestMatchers("/actuator/**", "/livez", "/readyz").permitAll()
                        // Flight schedules are public shopping data: a visitor
                        // searches and prices trips before any account exists, so
                        // reads of /api/flights are tokenless. Writes below still
                        // require ADMIN, so this opens the search surface only.
                        .requestMatchers(HttpMethod.GET, "/api/flights/**").permitAll()
                        // Reads of other reference data - any authenticated caller.
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        // Everything else (create/update/cancel/delete/generate) - ADMIN.
                        .anyRequest().hasRole("ADMIN")
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
