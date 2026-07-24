package com.skybook.praveen.e2e;

import io.restassured.path.json.JsonPath;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Reads claims out of a token for assertion purposes only.
 *
 * <p><b>Deliberately does not verify the signature.</b> The suite is a client:
 * it asserts on what it was handed, and the gateway plus every service already
 * verify properly (RS256, public key). Verifying here would only prove this test
 * can do RSA - and would need a key the suite has no business holding.
 */
public final class Jwt {

    private Jwt() {
    }

    public static List<String> roles(String token) {
        return JsonPath.from(payload(token)).getList("roles");
    }

    public static String subject(String token) {
        return JsonPath.from(payload(token)).getString("sub");
    }

    private static String payload(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("no token (login probably failed)");
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "not a JWT: " + (token.length() > 120 ? token.substring(0, 120) + "..." : token));
        }
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }
}
