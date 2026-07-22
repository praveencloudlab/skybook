package com.skybook.praveen.checkinservice.integration;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Full-application integration base: real PostgreSQL AND real Kafka. Scope
 * note (design doc section 17): this module's facade makes real synchronous
 * Feign calls for its orchestrated operations, which aren't in this container
 * set, so tests stay within the BookingEvent consumer's pure-DB
 * CONFIRMED/CANCELLED handling and the read-only REST endpoints.
 *
 * Now that the §4.4 matrix is enforced, the REST endpoints require a token: the
 * base signs an RS256 ADMIN token (ADMIN bypasses the per-owner check) and
 * attaches it to the shared {@link TestRestTemplate} so subclasses' requests are
 * authorized. The event-driven paths are unaffected (Kafka, not HTTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractCheckInIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    private static final KeyPair KEY_PAIR = generateRsa();
    private static final String ISSUER = "skybook-auth-test";
    private static final String AUDIENCE = "skybook-api-test";

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    private static KeyPair generateRsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("skybook.security.public-key",
                () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
        registry.add("skybook.security.issuer", () -> ISSUER);
        registry.add("skybook.security.user-audience", () -> AUDIENCE);
        registry.add("skybook.security.service-audience", () -> "checkin-service");
        // Real (>= 32-byte, non-default) boarding-pass key - BoardingPassTokenSigner
        // now fails boot on a missing/weak/default key (§5/§10).
        registry.add("checkin.boarding-pass.signing-key",
                () -> "integration-test-boarding-pass-signing-key-32bytes+");
    }

    @BeforeEach
    void attachAdminToken() {
        String token = Jwts.builder()
                .subject("it-admin@skybook.com")
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("token_type", "user")
                .claim("roles", List.of("ROLE_ADMIN"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith((RSAPrivateKey) KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
        restTemplate.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        });
    }
}
