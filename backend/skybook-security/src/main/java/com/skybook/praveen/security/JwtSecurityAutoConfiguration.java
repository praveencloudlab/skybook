package com.skybook.praveen.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.security.interfaces.RSAPublicKey;

/**
 * Wires the shared JWT infrastructure into any service that depends on
 * skybook-security, WITHOUT a per-service {@code @ComponentScan}
 * (SECURITY_HARDENING_MODULE.md §3.2). Registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * because this package is not scanned by an app rooted at
 * {@code com.skybook.praveen.<service>}.
 *
 * Deliberately provides the validator, key, handlers and properties as beans -
 * but NOT the {@link JwtAuthenticationFilter} as a bean. The filter is
 * constructed by each service inside its own SecurityFilterChain (§3.3, review
 * 4): a filter exposed as a bean can be auto-registered by the servlet
 * container outside Spring Security and run on paths it must not (e.g. the
 * client-credential {@code /service-token} chain). Each SecurityConfig builds
 * one via {@link #jwtAuthenticationFilter} only where it belongs.
 *
 * All beans are {@code @ConditionalOnMissingBean} so a service can override any
 * piece (e.g. the gateway supplies its own reactive wiring).
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtSecurityProperties.class)
public class JwtSecurityAutoConfiguration {

    /** Parses + strength-checks the RS256 public key at startup (fail closed). */
    @Bean
    @ConditionalOnMissingBean
    public RSAPublicKey jwtVerificationKey(JwtSecurityProperties properties) {
        return RsaPublicKeys.parse(properties.getPublicKey());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenValidator jwtTokenValidator(RSAPublicKey jwtVerificationKey, JwtSecurityProperties properties) {
        return new JwtTokenValidator(jwtVerificationKey, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonAccessDeniedHandler jsonAccessDeniedHandler(ObjectMapper objectMapper) {
        return new JsonAccessDeniedHandler(objectMapper);
    }

    /**
     * A NON-bean factory a service's SecurityConfig calls to build the filter
     * for its chain. Not annotated {@code @Bean} on purpose (see class javadoc).
     */
    public static JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenValidator validator, JsonAuthenticationEntryPoint entryPoint) {
        return new JwtAuthenticationFilter(validator, entryPoint);
    }
}
