package com.skybook.praveen.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.interfaces.RSAPublicKey;

/**
 * Wires the shared JWT infrastructure into any service that depends on
 * skybook-security, WITHOUT a per-service {@code @ComponentScan}
 * (SECURITY_HARDENING_MODULE.md §3.2). Registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * Two independently-activated concerns, so a service takes only what it needs:
 * <ul>
 *   <li><b>Validation</b> (key, validator, JSON handlers) - only when
 *       {@code skybook.security.public-key} is set. A service that only
 *       propagates a token outbound (not yet validating inbound) needs no key.</li>
 *   <li><b>Outbound identity</b> (Feign token propagation + the service-token
 *       provider) - only when Feign is on the classpath.</li>
 * </ul>
 *
 * The {@link JwtAuthenticationFilter} is never a bean (review 4): each service
 * builds one inside its own SecurityFilterChain via {@link #jwtAuthenticationFilter}.
 */
@AutoConfiguration
@EnableConfigurationProperties({JwtSecurityProperties.class, ServiceClientProperties.class})
public class JwtSecurityAutoConfiguration {

    /** Inbound token validation - active only once a verification key is configured. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "skybook.security", name = "public-key")
    static class ValidationConfiguration {

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
    }

    /** Outbound Feign identity - active only when Feign is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RequestInterceptor.class)
    static class FeignIdentityConfiguration {

        /**
         * The service-token provider, active only when this service has a client
         * credential (i.e. it makes authenticated service→service writes). Uses
         * the HTTP fetcher against auth-service's /service-token.
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "skybook.security.service-client", name = "client-id")
        public ServiceTokenProvider serviceTokenProvider(ServiceClientProperties properties) {
            return new ServiceTokenProvider(new HttpServiceTokenFetcher(properties));
        }
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
