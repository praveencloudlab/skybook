package com.skybook.praveen.authservice.sso;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The disabled world's owner of the SSO paths (SSO_MODULE.md §5): a click in
 * an environment with no Google client must land a HUMAN on the sign-in page
 * with words, not hand a browser a JSON 401 mid-navigation.
 */
class SsoDisabledControllerTest {

    @Test
    void answersWithTheHumanReadableRedirectOnThePublicOrigin() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SsoDisabledController("https://skybook.example").unavailable(response);

        // Absolute on purpose: a relative Location absolutizes against the
        // request host, which behind the proxy chain is the internal name.
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://skybook.example/login?error=sso_unavailable");
    }
}
