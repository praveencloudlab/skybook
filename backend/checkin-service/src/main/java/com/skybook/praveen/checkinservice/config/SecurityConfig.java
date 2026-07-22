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
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Back-office / gate - ADMIN.
                        .requestMatchers(HttpMethod.POST, "/api/checkins").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/checkins/*/open", "/api/checkins/*/board", "/api/checkins/*/gate")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/checkins/flight/**").hasRole("ADMIN")
                        .requestMatchers("/api/manifests/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/api/boarding-passes/verify", "/api/boarding-passes/*").hasRole("ADMIN")

                        // Passenger self-service - authenticated at the URL, OWNER
                        // enforced in the controller via CheckInAccessGuard.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
