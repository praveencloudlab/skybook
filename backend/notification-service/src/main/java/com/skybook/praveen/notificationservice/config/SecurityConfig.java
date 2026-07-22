package com.skybook.praveen.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Notification-service security (SECURITY_HARDENING_MODULE.md §3.2, §4.4).
 *
 * <p>Notification is a pure Kafka email consumer - it has NO business HTTP API,
 * so there is no authorization matrix to enforce. Its only HTTP surface is
 * actuator (Prometheus scrapes it over the internal network; step 10 moves the
 * management surface to an internal-only port). This chain therefore permits
 * actuator + CORS preflight and <b>denies everything else by default</b>, so a
 * controller added here later is locked down rather than accidentally open -
 * defense in depth without a JWT filter it has no tokens to validate.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Actuator stays scrapeable over the internal network
                        // (isolated to an internal management port in step 10).
                        .requestMatchers("/actuator/**").permitAll()
                        // No business endpoints exist; deny by default.
                        .anyRequest().denyAll()
                );

        return http.build();
    }
}
