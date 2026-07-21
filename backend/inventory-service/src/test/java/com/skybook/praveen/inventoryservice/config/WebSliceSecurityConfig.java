package com.skybook.praveen.inventoryservice.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permissive security for @WebMvcTest controller slices: the real
 * {@link SecurityConfig} needs the shared JWT validator/handler beans that a
 * web slice doesn't load. These tests assert controller behaviour (status
 * codes, bodies, validation) - the §4.4 authorization matrix is verified by the
 * full-stack integration test and live E2E instead.
 */
@TestConfiguration
public class WebSliceSecurityConfig {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
