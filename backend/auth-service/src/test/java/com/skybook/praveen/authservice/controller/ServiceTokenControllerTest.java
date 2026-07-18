package com.skybook.praveen.authservice.controller;

import com.skybook.praveen.authservice.dto.ServiceTokenRequest;
import com.skybook.praveen.authservice.entity.ServiceClient;
import com.skybook.praveen.authservice.repository.ServiceClientRepository;
import com.skybook.praveen.authservice.service.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTokenControllerTest {

    @Mock
    private ServiceClientRepository repository;
    @Mock
    private JwtService jwtService;
    @InjectMocks
    private ServiceTokenController controller;

    private Authentication authFor(String clientId) {
        return new UsernamePasswordAuthenticationToken(clientId, null);
    }

    private ServiceClient client(String id, String audiences) {
        ServiceClient c = new ServiceClient();
        c.setClientId(id);
        c.setAllowedAudiences(audiences);
        return c;
    }

    @Test
    void issuesATokenForAnAllowedAudience() {
        when(repository.findById("booking-service"))
                .thenReturn(Optional.of(client("booking-service", "flight-service,inventory-service")));
        when(jwtService.generateServiceToken("booking-service", "inventory-service")).thenReturn("the-token");

        var response = controller.issue(authFor("booking-service"),
                new ServiceTokenRequest("inventory-service"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("the-token");
    }

    @Test
    void rejectsAnAudienceNotOnTheClientsAllowlist() {
        when(repository.findById("booking-service"))
                .thenReturn(Optional.of(client("booking-service", "flight-service,inventory-service")));

        // booking-service is NOT allowed to target payment-service.
        assertThatThrownBy(() -> controller.issue(authFor("booking-service"),
                new ServiceTokenRequest("payment-service")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectsAnUnknownClient() {
        when(repository.findById("ghost-service")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.issue(authFor("ghost-service"),
                new ServiceTokenRequest("inventory-service")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
