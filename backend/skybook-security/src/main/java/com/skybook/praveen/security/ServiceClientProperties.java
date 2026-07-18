package com.skybook.praveen.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A service's own client credential for obtaining {@code ROLE_SERVICE} tokens
 * (SECURITY_HARDENING_MODULE.md §3.3). Set on services that make authenticated
 * service→service write calls (booking, check-in, payment, inventory); unset on
 * services that only propagate a user token or validate.
 */
@ConfigurationProperties(prefix = "skybook.security.service-client")
public class ServiceClientProperties {

    /** auth-service base URL for the (internal, non-gateway) token endpoint. */
    private String authBaseUrl;

    /** This service's client id (becomes the token {@code sub}). */
    private String clientId;

    /** This service's client secret (sent over HTTP Basic; never logged). */
    private String clientSecret;

    public String getAuthBaseUrl() {
        return authBaseUrl;
    }

    public void setAuthBaseUrl(String authBaseUrl) {
        this.authBaseUrl = authBaseUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}
