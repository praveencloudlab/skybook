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

                        // Back-office - ADMIN. list-all + search + confirm + complete.
                        .requestMatchers(HttpMethod.GET, "/api/bookings").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/bookings/search").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/bookings/*/confirm", "/api/bookings/*/complete")
                        .hasRole("ADMIN")

                        // Everything else (create, quote, own get/reference/cancel,
                        // passenger check-in/board) - authenticated; OWNER enforced in
                        // the controller via BookingAccessGuard.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
