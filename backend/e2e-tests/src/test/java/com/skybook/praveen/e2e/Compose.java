package com.skybook.praveen.e2e;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The one place the suite reaches outside the gateway (§1.3).
 *
 * <p>Stopping a container is the only honest way to certify "what happens when a
 * dependency dies" - it cannot be provoked through the public API, which is
 * exactly why that scenario usually goes untested. Confined to this class so the
 * coupling to compose is visible and singular rather than smeared through tests.
 *
 * <p>On Windows/Git Bash {@code docker compose} is invoked directly (not through
 * a shell) to avoid the path mangling documented in the root README.
 */
public final class Compose {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(90);

    private Compose() {
    }

    public static void stop(String service) {
        run("stop", service);
    }

    public static void start(String service) {
        run("start", service);
    }

    /** True when compose reports the container healthy. */
    public static boolean isHealthy(String service) {
        String status = run("ps", service, "--format", "{{.Status}}");
        return status.contains("healthy");
    }

    /** Blocks until the service is healthy again, so recovery is real not assumed. */
    public static void awaitHealthy(String service, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isHealthy(service)) {
                return;
            }
            sleep(2000);
        }
        throw new IllegalStateException(
                service + " did not become healthy within " + timeout.toSeconds() + "s");
    }

    private static String run(String... composeArgs) {
        List<String> command = new ArrayList<>(List.of("docker", "compose"));
        command.addAll(List.of(composeArgs));

        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(repoRoot())
                    .redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("`" + String.join(" ", command) + "` timed out");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("""
                    Could not run `%s`.
                    The service-down case needs the docker CLI on PATH and the fleet running
                    from the repo root.""".formatted(String.join(" ", command)), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + String.join(" ", command), e);
        }
    }

    /** The module runs from backend/e2e-tests; compose files live two levels up. */
    private static java.io.File repoRoot() {
        return new java.io.File(System.getProperty("user.dir")).toPath()
                .resolve("../..").normalize().toFile();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
