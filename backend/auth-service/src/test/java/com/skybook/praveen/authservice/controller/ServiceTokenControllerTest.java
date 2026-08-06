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

    // ------------------------------------------------------ guest tokens
    // GUEST_CHECKIN_MODULE.md §3.1: same chain, same client model, one extra
    // gate - the explicit may_issue_guest_tokens grant.

    @Test
    void issuesAGuestTokenForTheGrantedClient() {
        ServiceClient booking = client("booking-service", "flight-service,inventory-service");
        booking.setMayIssueGuestTokens(true);
        when(repository.findById("booking-service")).thenReturn(Optional.of(booking));
        when(jwtService.generateGuestToken(41L)).thenReturn("the-guest-token");

        var response = controller.issueGuestToken(authFor("booking-service"),
                new com.skybook.praveen.authservice.dto.GuestTokenRequest(41L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("the-guest-token");
    }

    @Test
    void refusesAClientWithoutTheGuestGrant() {
        // checkin-service has valid credentials and audiences - but minting a
        // browser-facing session is a SEPARATE privilege it does not hold.
        when(repository.findById("checkin-service"))
                .thenReturn(Optional.of(client("checkin-service", "flight-service,inventory-service")));

        assertThatThrownBy(() -> controller.issueGuestToken(authFor("checkin-service"),
                new com.skybook.praveen.authservice.dto.GuestTokenRequest(41L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void refusesAnUnknownClientAskingForGuestTokens() {
        when(repository.findById("ghost-service")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.issueGuestToken(authFor("ghost-service"),
                new com.skybook.praveen.authservice.dto.GuestTokenRequest(41L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
