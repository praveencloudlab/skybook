package com.skybook.praveen.authservice.sso;

import java.util.regex.Pattern;

/**
 * The open-redirect defence (SSO_MODULE.md §3.3). A {@code returnTo} must be a
 * same-origin absolute path - one leading slash, never two.
 *
 * <p>The negative lookahead is load-bearing and easy to miss: a naive "starts
 * with /" check admits {@code //evil.com}, which browsers resolve as a
 * <em>protocol-relative</em> URL to another host. Backslash is excluded from
 * the character class for the same reason - some browsers treat {@code /\} as
 * {@code //}. Anything that fails the pattern becomes "/", silently: a broken
 * return path should cost the user their page position, never their safety.
 */
public final class SafeReturnTo {

    private static final Pattern SAFE_PATH = Pattern.compile("^/(?!/)[A-Za-z0-9/._~?=&%-]*$");

    private SafeReturnTo() {
    }

    public static String sanitize(String candidate) {
        if (candidate == null || !SAFE_PATH.matcher(candidate).matches()) {
            return "/";
        }
        return candidate;
    }
}
