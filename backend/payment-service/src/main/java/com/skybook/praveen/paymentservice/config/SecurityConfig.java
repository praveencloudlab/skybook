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
                        .requestMatchers("/actuator/health/**").permitAll()
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
