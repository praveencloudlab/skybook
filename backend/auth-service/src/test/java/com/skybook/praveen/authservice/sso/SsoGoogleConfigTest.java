package com.skybook.praveen.authservice.sso;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production registration wiring (SSO_MODULE.md §6.1/§6.2) - the flow test
 * proves the machinery against a stub registration, so THIS class pins the
 * things only the real config decides: where the redirect URI comes from and
 * what Google is asked for.
 */
class SsoGoogleConfigTest {

    private final SsoGoogleConfig config = new SsoGoogleConfig();

    private ClientRegistration google(String baseUrl) {
        SsoProperties properties = new SsoProperties();
        properties.setClientId("real-client-id");
        properties.setClientSecret("real-client-secret");
        ClientRegistrationRepository repository =
                config.clientRegistrationRepository(properties, baseUrl);
        return repository.findByRegistrationId("google");
    }

    @Test
    void theRedirectUriComesFromConfigurationNeverFromRequestHeaders() {
        // §6.2: redirect-URI correctness must not depend on forwarded-header
        // trust - it is APP_PUBLIC_BASE_URL plus the fixed callback path.
        ClientRegistration registration = google("https://skybook.example");

        assertThat(registration.getRedirectUri())
                .isEqualTo("https://skybook.example/api/auth/oauth2/callback/google");
    }

    @Test
    void asksGoogleForExactlyTheOidcTriple() {
        ClientRegistration registration = google("http://localhost:3000");

        assertThat(registration.getScopes())
                .containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(registration.getClientId()).isEqualTo("real-client-id");
    }

    @Test
    void theResolverIsBuiltAgainstTheRegistrationItWillResolve() {
        SsoProperties properties = new SsoProperties();
        properties.setClientId("real-client-id");
        properties.setClientSecret("real-client-secret");
        ClientRegistrationRepository repository =
                config.clientRegistrationRepository(properties, "http://localhost:3000");

        assertThat(config.authorizationRequestResolver(repository)).isNotNull();
    }
}
