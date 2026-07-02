package com.skybook.praveen.bookingservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permit-all placeholder, mirroring auth-service's SecurityConfig structure
 * (csrf disabled, authorizeHttpRequests) minus the JWT filter - booking-service
 * doesn't validate tokens yet. This exists now so the dependency and config
 * shape are in place; swap `.anyRequest().permitAll()` for real JWT-based
 * authorization once auth-service issues tokens this service can verify.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}
