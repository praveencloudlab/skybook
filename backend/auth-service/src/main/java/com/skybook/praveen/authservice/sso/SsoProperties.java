package com.skybook.praveen.authservice.sso;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SSO configuration (SSO_MODULE.md §6.1). An empty client id means the feature
 * is OFF - deliberately an empty default rather than a required secret: SSO is
 * a feature, not an invariant, and a fresh clone or a CI rung must boot
 * without any Google configuration and behave exactly as before the feature
 * existed.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "skybook.sso.google")
public class SsoProperties {

    private String clientId = "";
    private String clientSecret = "";

    public boolean enabled() {
        return StringUtils.hasText(clientId);
    }
}
