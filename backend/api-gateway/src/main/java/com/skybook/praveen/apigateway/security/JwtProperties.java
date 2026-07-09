package com.skybook.praveen.apigateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Mirrors auth-service's JwtProperties - same jwt.secret key, must resolve to the identical value (design doc §11). */
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
