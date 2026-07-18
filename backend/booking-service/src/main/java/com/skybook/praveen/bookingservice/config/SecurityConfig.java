package com.skybook.praveen.bookingservice.config;

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
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenValidator validator,
                                                   JsonAuthenticationEntryPoint entryPoint) throws Exception {

        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(validator, entryPoint);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Only booking creation is enforced now - it must have a
                        // principal to own the booking. The rest of the matrix
                        // flips in step 6 (authentication-only rollout).
                        .requestMatchers(HttpMethod.POST, "/api/bookings").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
