package com.skybook.praveen.authservice.sso;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;

/**
 * The Google client registration, built programmatically and ONLY when a
 * client id is configured (SSO_MODULE.md §6.1). The alternative - declaring it
 * under {@code spring.security.oauth2.client.registration.*} in application.yml
 * - would make an unset GOOGLE_CLIENT_ID a boot failure for every rung of the
 * ladder; feature-off must be a first-class, boring state.
 *
 * <p>These beans existing is what "SSO is enabled" means: SecurityConfig turns
 * on {@code oauth2Login} iff a ClientRegistrationRepository is present, and
 * the disabled-mode fallback controller condition is this one inverted.
 */
@Configuration
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${skybook.sso.google.client-id:}')")
public class SsoGoogleConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            SsoProperties properties,
            // The redirect URI is built from configuration, NEVER from request
            // headers (§6.2): redirect-URI correctness must not depend on
            // forwarded-header trust. Same property the reset-link fix uses.
            @Value("${app.public-base-url:http://localhost:5173}") String publicBaseUrl) {

        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .redirectUri(publicBaseUrl + "/api/auth/oauth2/callback/google")
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {

        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/api/auth/oauth2/authorization");
        // PKCE for a confidential client (§3.4): Google supports it, and with
        // the code challenge in play a leaked authorization code is worthless
        // on its own. Strictly stronger, costs one customizer.
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }
}
