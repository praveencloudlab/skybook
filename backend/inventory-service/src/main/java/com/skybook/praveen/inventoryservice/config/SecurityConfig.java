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
