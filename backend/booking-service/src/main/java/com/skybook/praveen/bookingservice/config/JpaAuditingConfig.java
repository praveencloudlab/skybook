package com.skybook.praveen.bookingservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * Same situation as flight-service: no Spring Security / JWT validation
     * wired into this service yet, so there's no real authenticated
     * principal to stamp into createdBy / updatedBy.
     *
     * TODO: once JWT validation is added, replace with a bean reading the
     * authenticated principal out of the SecurityContext.
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}
