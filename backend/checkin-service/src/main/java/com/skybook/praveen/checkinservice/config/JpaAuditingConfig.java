package com.skybook.praveen.checkinservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * Same situation as the sibling services: no JWT validation wired in
     * yet, so no real authenticated principal to stamp into
     * createdBy/updatedBy.
     *
     * TODO: once JWT validation is added, replace with a bean reading the
     * authenticated principal out of the SecurityContext.
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}
