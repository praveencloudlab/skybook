package com.skybook.praveen.e2e;

/**
 * Environment wiring for the certification suite
 * (E2E_CERTIFICATION_MODULE.md §3/§4).
 *
 * <p>Everything is overridable so the same suite can later be pointed at a
 * Kubernetes ingress without a rewrite (§11). Resolution order per setting is
 * system property → environment variable → default.
 *
 * <p><b>Only the gateway is addressed.</b> Since the security branch, every
 * service port, Postgres and Kafka are {@code expose:}-only, so the gateway is
 * both the sole reachable entry point and the correct trust boundary to certify
 * against (§1.3).
 */
public final class E2EConfig {

    private E2EConfig() {
    }

    /** The public edge - the ONLY host-reachable application port. */
    public static final String BASE_URL = resolve("e2e.baseUrl", "E2E_BASE_URL", "http://localhost:8080");

    /** Tempo, for the cross-service trace assertion (§7). */
    public static final String TEMPO_URL = resolve("e2e.tempoUrl", "E2E_TEMPO_URL", "http://localhost:3200");

    /**
     * An account that auth-service has promoted to ADMIN via
     * {@code SKYBOOK_BOOTSTRAP_ADMIN_EMAIL}. There is no API to promote a user
     * (§1.4), so this has to be arranged out of band - the preflight explains how
     * when it is missing.
     */
    public static final String ADMIN_EMAIL = resolve("e2e.admin.email", "E2E_ADMIN_EMAIL", null);
    public static final String ADMIN_PASSWORD = resolve("e2e.admin.password", "E2E_ADMIN_PASSWORD", null);

    /** Distinguishes concurrent/repeat runs so they never collide (§4). */
    public static final String RUN_ID = Long.toString(System.currentTimeMillis());

    /** Meets the registration complexity policy (12+, upper/lower/digit/symbol). */
    public static final String USER_PASSWORD = "E2ePassw0rd!";

    public static boolean adminConfigured() {
        return notBlank(ADMIN_EMAIL) && notBlank(ADMIN_PASSWORD);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String resolve(String systemProperty, String envVar, String fallback) {
        String value = System.getProperty(systemProperty);
        if (notBlank(value)) {
            return value;
        }
        value = System.getenv(envVar);
        return notBlank(value) ? value : fallback;
    }
}
