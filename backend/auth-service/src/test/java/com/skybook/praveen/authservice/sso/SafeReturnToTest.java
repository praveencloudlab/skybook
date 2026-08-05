package com.skybook.praveen.authservice.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The open-redirect defence (SSO_MODULE.md §3.3), exercised as a table of the
 * ways redirect-path validation is classically defeated. The dangerous cases
 * matter more than the happy ones: every rejected input here is a real attack
 * shape, not an invented string.
 */
class SafeReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/trips",
            "/flights/search?from=LHR&to=DXB",
            "/reset-password?token=abc-123_XY",
            "/deep/nested/path.html",
            "/percent%20encoded"
    })
    void keepsHonestSameOriginPaths(String path) {
        assertThat(SafeReturnTo.sanitize(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The classic: protocol-relative, resolves to ANOTHER HOST.
            "//evil.com",
            "//evil.com/phish",
            // Absolute URLs, with and without scheme confusion.
            "https://evil.com",
            "http://evil.com/",
            "javascript:alert(1)",
            // Backslash variants - some browsers treat /\ like //.
            "/\\evil.com",
            "\\\\evil.com",
            // Scheme-ish and header-injection shapes.
            "evil.com/naked-host",
            "/with space",
            "/crlf%0d%0aSet-Cookie:x"
    })
    void collapsesEveryHostileShapeToTheRoot(String hostile) {
        assertThat(SafeReturnTo.sanitize(hostile)).isEqualTo("/");
    }

    @Test
    void nullBecomesTheRootNotAnException() {
        assertThat(SafeReturnTo.sanitize(null)).isEqualTo("/");
    }
}
