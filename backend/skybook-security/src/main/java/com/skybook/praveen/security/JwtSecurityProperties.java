package com.skybook.praveen.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code skybook.security.*} (SECURITY_HARDENING_MODULE.md §5). Every
 * consuming service configures the RS256 public key, the issuer, and the two
 * audiences (user vs. this service's own service-audience). No signing key
 * lives here - only auth-service holds the private key.
 */
@ConfigurationProperties(prefix = "skybook.security")
public class JwtSecurityProperties {

    /**
     * RS256 PUBLIC key (PEM, base64 SubjectPublicKeyInfo, with or without the
     * {@code -----BEGIN PUBLIC KEY-----} armor). Verify-only: this module can
     * never mint a token. Required; boot fails closed if missing/malformed.
     */
    private String publicKey;

    /** Required {@code iss} claim, e.g. {@code skybook-auth-prod}. */
    private String issuer;

    /** Audience a {@code token_type=user} token must carry, e.g. {@code skybook-api-prod}. */
    private String userAudience;

    /**
     * This service's own name - the audience a {@code token_type=service} token
     * must carry to be accepted here (e.g. {@code inventory-service}). Unset on
     * services that never receive service tokens.
     */
    private String serviceAudience;

    /**
     * Whether this validator accepts {@code token_type=service} tokens at all.
     * The gateway sets this false so a machine token can never enter through the
     * public edge (§5); internal services leave it true.
     */
    private boolean acceptServiceTokens = true;

    /**
     * Rollout flag (§3.2). When false, the filter still validates a PRESENT
     * token and populates the SecurityContext, but an ABSENT token is allowed
     * through (authorization rules, layered on top, are what actually enforce).
     * Defaults true; only the test profile turns it off. It sequences the §13
     * rollout - it is never meant to ship disabled.
     */
    private boolean enforcementEnabled = true;

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getUserAudience() {
        return userAudience;
    }

    public void setUserAudience(String userAudience) {
        this.userAudience = userAudience;
    }

    public String getServiceAudience() {
        return serviceAudience;
    }

    public void setServiceAudience(String serviceAudience) {
        this.serviceAudience = serviceAudience;
    }

    public boolean isAcceptServiceTokens() {
        return acceptServiceTokens;
    }

    public void setAcceptServiceTokens(boolean acceptServiceTokens) {
        this.acceptServiceTokens = acceptServiceTokens;
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public void setEnforcementEnabled(boolean enforcementEnabled) {
        this.enforcementEnabled = enforcementEnabled;
    }
}
