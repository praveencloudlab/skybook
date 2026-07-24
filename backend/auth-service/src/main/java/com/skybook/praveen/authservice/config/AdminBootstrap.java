package com.skybook.praveen.authservice.config;

import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Promotes the configured bootstrap user to ADMIN once at startup
 * (SECURITY_HARDENING_MODULE.md §4.3) - a property, not brittle migration SQL
 * that assumes a specific row exists. Idempotent; warns loudly if no
 * administrator exists in the system at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap {

    private final UserRepository userRepository;

    @Value("${skybook.bootstrap-admin-email:}")
    private String bootstrapAdminEmail;

    @EventListener(ApplicationReadyEvent.class)
    public void promoteBootstrapAdmin() {

        if (bootstrapAdminEmail != null && !bootstrapAdminEmail.isBlank()) {
            String email = bootstrapAdminEmail.trim().toLowerCase(Locale.ROOT);
            userRepository.findByEmail(email).ifPresentOrElse(user -> {
                if (user.getRole() != UserRole.ADMIN) {
                    user.setRole(UserRole.ADMIN);
                    userRepository.save(user);
                    log.info("Bootstrap: promoted {} to ADMIN", email);
                }
            }, () -> log.warn("Bootstrap admin email {} not found - no promotion performed", email));
        }

        if (!userRepository.existsByRole(UserRole.ADMIN)) {
            log.warn("SECURITY: no ADMIN user exists. Set SKYBOOK_BOOTSTRAP_ADMIN_EMAIL to an existing "
                    + "registered user to grant administrative access.");
        }
    }
}
