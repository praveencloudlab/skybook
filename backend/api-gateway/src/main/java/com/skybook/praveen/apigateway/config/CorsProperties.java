package com.skybook.praveen.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Binds gateway.cors.* (design doc §5) - the only place CORS is configured anywhere in the fleet. */
@ConfigurationProperties(prefix = "gateway.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
