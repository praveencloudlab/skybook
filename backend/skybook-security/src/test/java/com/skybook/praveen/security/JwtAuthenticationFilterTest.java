package com.skybook.praveen.security;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authentication filter (SECURITY_HARDENING_MODULE.md §3.2) driven end to
 * end through a real {@link JwtTokenValidator} - a mocked validator would only
 * prove the filter calls a method, not that a forged or lapsed token actually
 * fails to authenticate. The three outcomes that matter are asserted
 * separately: authenticated, deliberately anonymous, and rejected.
 */
class JwtAuthenticationFilterTest {

    private final TestTokens tokens = TestTokens.rsa2048();
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            new JwtTokenValidator(tokens.publicKey(), tokens.properties()),
            new JsonAuthenticationEntryPoint(serviceLikeObjectMapper()));

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    /**
     * Stands in for the mapper a consuming service injects. Every service that
     * uses this module pulls jackson-datatype-jsr310 in through the web starter,
     * so the ErrorResponse timestamp serializes; this module itself declares only
     * jackson-databind, so the JSR-310 handling has to be supplied here rather
     * than letting the fixture fail on something production always has.
     */
    private static ObjectMapper serviceLikeObjectMapper() {
        SimpleModule javaTime = new SimpleModule();
        javaTime.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new ObjectMapper().registerModule(javaTime);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Nested
    @DisplayName("a valid token establishes the caller's identity")
    class ValidToken {

        @Test
        void putsTheVerifiedPrincipalAndItsRolesIntoTheContext() throws Exception {
            MockHttpServletRequest request = get("/api/bookings");
            request.addHeader("Authorization", "Bearer " + tokens.token().sign());

            filter.doFilter(request, response, chain);

            Authentication authentication = currentAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.getPrincipal()).isInstanceOf(AuthenticatedPrincipal.class);
            assertThat(authentication.getName()).isEqualTo("alice@example.com");
            assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
            assertThat(chain.getRequest()).isSameAs(request);
        }

        @Test
        void carriesTheTokenTypeThroughSoOwnershipChecksCanTellUsersFromServices() throws Exception {
            MockHttpServletRequest request = get("/api/inventory/holds");
            request.addHeader("Authorization", "Bearer " + tokens.token()
                    .tokenType("service").roles("ROLE_SERVICE")
                    .audience(TestTokens.SERVICE_AUDIENCE).subject("booking-service").sign());

            filter.doFilter(request, response, chain);

            AuthenticatedPrincipal principal =
                    (AuthenticatedPrincipal) currentAuthentication().getPrincipal();
            assertThat(principal.tokenType()).isEqualTo(TokenType.SERVICE);
            assertThat(principal.subject()).isEqualTo("booking-service");
        }

        @Test
        void leavesTheCredentialsNullSoTheTokenIsNeverReExposed() throws Exception {
            MockHttpServletRequest request = get("/api/bookings");
            request.addHeader("Authorization", "Bearer " + tokens.token().sign());

            filter.doFilter(request, response, chain);

            assertThat(currentAuthentication().getCredentials()).isNull();
        }
    }

    @Nested
    @DisplayName("no credential means anonymous, never an invented identity")
    class NoCredential {

        @Test
        void passesThroughUnauthenticatedWhenThereIsNoAuthorizationHeader() throws Exception {
            MockHttpServletRequest request = get("/api/flights");

            filter.doFilter(request, response, chain);

            assertThat(currentAuthentication()).isNull();
            assertThat(chain.getRequest()).isSameAs(request);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        void ignoresAnAuthorizationSchemeThatIsNotBearer() throws Exception {
            MockHttpServletRequest request = get("/api/flights");
            request.addHeader("Authorization", "Basic Ym9va2luZzpzZWNyZXQ=");

            filter.doFilter(request, response, chain);

            assertThat(currentAuthentication()).isNull();
            assertThat(chain.getRequest()).isSameAs(request);
        }

        @Test
        void skipsCorsPreflightEntirely() throws Exception {
            // A browser never attaches Authorization to a preflight, so filtering
            // OPTIONS would reject the probe that decides whether the real
            // request is allowed to happen at all.
            MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/bookings");
            preflight.addHeader("Authorization", "Bearer " + tokens.token()
                    .expiry(Instant.now().minus(1, ChronoUnit.MINUTES)).sign());

            filter.doFilter(preflight, response, chain);

            assertThat(chain.getRequest()).isSameAs(preflight);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("a bad token is rejected outright, never downgraded to anonymous")
    class RejectedToken {

        @Test
        void answers401AndStopsTheChainForAnExpiredToken() throws Exception {
            MockHttpServletRequest request = get("/api/bookings");
            request.addHeader("Authorization", "Bearer " + tokens.token()
                    .expiry(Instant.now().minus(1, ChronoUnit.MINUTES)).sign());

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).contains("application/json");
            assertThat(response.getContentAsString()).contains("Authentication required");
            // The request must never reach the handler behind the filter.
            assertThat(chain.getRequest()).isNull();
            assertThat(currentAuthentication()).isNull();
        }

        @Test
        void answers401ForATokenSignedByAKeyThatIsNotAuthServices() throws Exception {
            TestTokens forger = TestTokens.rsa2048();
            MockHttpServletRequest request = get("/api/bookings");
            request.addHeader("Authorization", "Bearer " + forger.token().sign());

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        void answers401ForATamperedPayload() throws Exception {
            // Flip a character in the payload segment: the claims no longer match
            // the signature, so verification fails before any claim is trusted.
            String[] parts = tokens.token().sign().split("\\.");
            String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1)
                    + (parts[1].endsWith("A") ? "B" : "A") + "." + parts[2];

            MockHttpServletRequest request = get("/api/bookings");
            request.addHeader("Authorization", "Bearer " + tampered);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        void answers401ForAnEmptyBearerValue() throws Exception {
            MockHttpServletRequest request = get("/api/bookings");
            request.addHeader("Authorization", "Bearer ");

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        void discardsAnyIdentityAlreadySittingInTheContext() throws Exception {
            // Thread reuse in a servlet container: a leftover authentication must
            // not survive a request that presents a bad token.
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("stale@example.com", null));

            MockHttpServletRequest request = get("/api/bookings");
            request.addHeader("Authorization", "Bearer not-a-jwt");

            filter.doFilter(request, response, chain);

            assertThat(currentAuthentication()).isNull();
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }
}
