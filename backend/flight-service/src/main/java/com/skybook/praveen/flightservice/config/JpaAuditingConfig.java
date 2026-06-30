package com.skybook.praveen.flightservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * flight-service does not yet validate JWTs or expose an authenticated
     * principal (no Spring Security on the classpath here), so createdBy /
     * updatedBy are stamped with a fixed placeholder for now.
     *
     * TODO: once JWT validation is wired into this service, replace this with
     * a bean that reads the authenticated username/email out of the
     * SecurityContext, e.g.:
     *   SecurityContextHolder.getContext().getAuthentication().getName()
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}
