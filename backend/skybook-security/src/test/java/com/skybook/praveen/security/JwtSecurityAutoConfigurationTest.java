package com.skybook.praveen.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the module auto-configures from its {@code AutoConfiguration.imports}
 * (no @ComponentScan in the consuming service) and fails closed on a bad key.
 */
class JwtSecurityAutoConfigurationTest {

    private final TestTokens tokens = TestTokens.rsa2048();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withConfiguration(AutoConfigurations.of(JwtSecurityAutoConfiguration.class));

    @Test
    void registersTheSharedBeansWhenConfigured() {
        runner.withPropertyValues(
                        "skybook.security.public-key=" + tokens.publicKeyBase64OneLine(),
                        "skybook.security.issuer=" + TestTokens.ISSUER,
                        "skybook.security.user-audience=" + TestTokens.USER_AUDIENCE,
                        "skybook.security.service-audience=inventory-service")
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtTokenValidator.class);
                    assertThat(context).hasSingleBean(RSAPublicKey.class);
                    assertThat(context).hasSingleBean(JsonAuthenticationEntryPoint.class);
                    assertThat(context).hasSingleBean(JsonAccessDeniedHandler.class);
                    // The filter is deliberately NOT a bean (§3.3): services build
                    // it inside their own chain so it can't be auto-registered.
                    assertThat(context).doesNotHaveBean(JwtAuthenticationFilter.class);
                });
    }

    @Test
    void skipsValidationWhenNoPublicKeyConfigured() {
        // A propagation-only service (e.g. inventory before step 6) has no key -
        // it must start WITHOUT a validator, not fail (§3.3 refactor).
        runner.withPropertyValues(
                        "skybook.security.issuer=" + TestTokens.ISSUER,
                        "skybook.security.user-audience=" + TestTokens.USER_AUDIENCE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JwtTokenValidator.class);
                    assertThat(context).doesNotHaveBean(RSAPublicKey.class);
                });
    }

    @Test
    void failsClosedOnAMalformedPublicKey() {
        // A service that DOES configure a key but supplies garbage must fail at
        // boot rather than run unable to verify anything.
        runner.withPropertyValues(
                        "skybook.security.public-key=not-a-real-key",
                        "skybook.security.issuer=" + TestTokens.ISSUER,
                        "skybook.security.user-audience=" + TestTokens.USER_AUDIENCE)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void registersTheServiceTokenProviderWhenAClientCredentialIsSet() {
        // The outbound-identity concern activates independently of validation:
        // a client-id present + Feign on the classpath -> a token provider.
        runner.withPropertyValues(
                        "skybook.security.service-client.client-id=booking-service",
                        "skybook.security.service-client.client-secret=secret",
                        "skybook.security.service-client.auth-base-url=http://localhost:8081")
                .run(context -> assertThat(context).hasSingleBean(ServiceTokenProvider.class));
    }
}
