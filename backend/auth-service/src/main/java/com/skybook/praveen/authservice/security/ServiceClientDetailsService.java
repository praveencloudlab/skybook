package com.skybook.praveen.authservice.security;

import com.skybook.praveen.authservice.repository.ServiceClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Backs the client-credential ({@code @Order(1)}) filter chain for
 * {@code /api/auth/service-token} (SECURITY_HARDENING_MODULE.md §3.3). Looks up
 * a {@link com.skybook.praveen.authservice.entity.ServiceClient} by its id and
 * hands its BCrypt hash to the DaoAuthenticationProvider for constant-time
 * verification - an unknown client and a wrong secret both surface as the same
 * BadCredentials 401 (no distinction leaked).
 */
@Service
@RequiredArgsConstructor
public class ServiceClientDetailsService implements UserDetailsService {

    private final ServiceClientRepository repository;

    @Override
    public UserDetails loadUserByUsername(String clientId) throws UsernameNotFoundException {
        return repository.findById(clientId)
                .map(client -> User.withUsername(client.getClientId())
                        .password(client.getSecretHash())
                        .disabled(!client.isEnabled())
                        .authorities(AuthorityUtils.createAuthorityList("ROLE_SERVICE_CLIENT"))
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown service client"));
    }
}
