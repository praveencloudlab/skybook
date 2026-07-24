package com.skybook.praveen.authservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Provisioning source for the service-client registry
 * (SECURITY_HARDENING_MODULE.md §4.5). Each entry names a machine caller, its
 * raw secret (hashed with BCrypt at bootstrap - never stored or logged in the
 * clear), and the audiences it may target. Supplied via deploy properties/env,
 * not committed in migration SQL.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "skybook.service-registry")
public class ServiceRegistryProperties {

    private List<Client> clients = new ArrayList<>();

    @Getter
    @Setter
    public static class Client {
        private String clientId;
        private String secret;
        /** Comma-separated audiences this client may request tokens for. */
        private String allowedAudiences;
    }
}
