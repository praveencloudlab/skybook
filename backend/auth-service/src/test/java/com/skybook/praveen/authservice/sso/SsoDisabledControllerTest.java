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
    void answersWithTheHumanReadableRedirect() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SsoDisabledController().unavailable(response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=sso_unavailable");
    }
}
