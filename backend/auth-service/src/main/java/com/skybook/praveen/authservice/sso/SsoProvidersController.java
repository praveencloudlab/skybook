package com.skybook.praveen.authservice.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Runtime provider discovery (SSO_MODULE.md §3.2, decision D4). The frontend
 * image is built once and promoted through every environment, so "is Google
 * sign-in available HERE" must be runtime data - a build-time flag would fork
 * the artifact per environment and break the ladder's core principle.
 *
 * <p>Public and unauthenticated by design: it reveals only which buttons the
 * sign-in page should draw, which the sign-in page would reveal anyway.
 */
@RestController
@RequiredArgsConstructor
public class SsoProvidersController {

    private final SsoProperties properties;

    @GetMapping("/api/auth/sso/providers")
    public List<String> providers() {
        return properties.enabled() ? List.of(SsoAccountService.PROVIDER_GOOGLE) : List.of();
    }
}
