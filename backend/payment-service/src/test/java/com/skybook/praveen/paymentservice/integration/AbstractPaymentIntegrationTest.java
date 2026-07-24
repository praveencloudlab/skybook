package com.skybook.praveen.paymentservice.integration;

import io.jsonwebtoken.Jwts;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Full-application integration base: real PostgreSQL AND real Kafka (the
 * facade publishes on every orchestrated operation, and the BookingEvent
 * consumer subscribes at startup - unlike inventory, this service cannot
 * boot meaningfully without a broker). Singleton containers, shared context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPaymentIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    // RS256 keypair: the app verifies with the public key, the test signs an
    // ADMIN token with the matching private key (ADMIN satisfies every §4.4 rule).
    private static final KeyPair KEYS = generateRsa();

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private static KeyPair generateRsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A valid ADMIN user token; the API IT attaches it to every request. */
    protected static String adminToken() {
        return Jwts.builder()
                .subject("admin@skybook.com")
                .issuer("skybook-auth")
                .audience().add("skybook-api").and()
                .claim("token_type", "user").claim("roles", List.of("ROLE_ADMIN"))
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith((RSAPrivateKey) KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("skybook.security.public-key",
                () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        registry.add("skybook.security.issuer", () -> "skybook-auth");
        registry.add("skybook.security.user-audience", () -> "skybook-api");
        registry.add("skybook.security.service-audience", () -> "payment-service");
    }
}
