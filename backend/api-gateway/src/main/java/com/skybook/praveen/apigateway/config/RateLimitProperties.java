package com.skybook.praveen.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds gateway.rate-limit.* (design doc §6). */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitProperties {

    private int requestsPerMinute = 100;

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }
}
