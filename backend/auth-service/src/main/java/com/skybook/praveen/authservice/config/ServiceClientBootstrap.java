package com.skybook.praveen.authservice.config;

import com.skybook.praveen.authservice.entity.ServiceClient;
import com.skybook.praveen.authservice.repository.ServiceClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Provisions the service-client registry at startup from
 * {@link ServiceRegistryProperties} (SECURITY_HARDENING_MODULE.md §4.5) - the
 * "one-time administrative bootstrap that hashes the supplied secret", so no
 * plaintext credential is ever committed in migration SQL. Idempotent: inserts
 * a missing client (BCrypt-hashing its secret) and keeps an existing client's
 * allowed audiences in sync, without churning its stored hash.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ServiceRegistryProperties.class)
public class ServiceClientBootstrap {

    private final ServiceRegistryProperties properties;
    private final ServiceClientRepository repository;
    private final PasswordEncoder passwordEncoder;

    @EventListener(ApplicationReadyEvent.class)
    public void provision() {
        for (ServiceRegistryProperties.Client def : properties.getClients()) {
            if (def.getClientId() == null || def.getClientId().isBlank()) {
                continue;
            }
            repository.findById(def.getClientId()).ifPresentOrElse(existing -> {
                if (!existing.getAllowedAudiences().equals(def.getAllowedAudiences())) {
                    existing.setAllowedAudiences(def.getAllowedAudiences());
                    existing.setUpdatedAt(LocalDateTime.now());
                    repository.save(existing);
                    log.info("Service client {} audiences updated", def.getClientId());
                }
            }, () -> {
                ServiceClient client = new ServiceClient();
                client.setClientId(def.getClientId());
                client.setSecretHash(passwordEncoder.encode(def.getSecret()));
                client.setAllowedAudiences(def.getAllowedAudiences());
                client.setEnabled(true);
                repository.save(client);
                log.info("Registered service client {} (audiences: {})",
                        def.getClientId(), def.getAllowedAudiences());
            });
        }
    }
}
