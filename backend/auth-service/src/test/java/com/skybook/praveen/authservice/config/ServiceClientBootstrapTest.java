package com.skybook.praveen.authservice.config;

import com.skybook.praveen.authservice.entity.ServiceClient;
import com.skybook.praveen.authservice.repository.ServiceClientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service-client provisioning (SECURITY_HARDENING_MODULE.md §4.5). The deploy
 * configuration is authoritative in both directions: a new entry is registered
 * with its secret hashed, and an entry that DISAPPEARS from the config is
 * deprovisioned - otherwise removing a client from config would revoke nothing.
 */
@ExtendWith(MockitoExtension.class)
class ServiceClientBootstrapTest {

    private static final String BOOKING = "booking-service";
    private static final String RAW_SECRET = "booking-secret";
    private static final String HASHED_SECRET = "$2a$10$hashed-booking-secret";
    private static final String AUDIENCES = "inventory-service,flight-service";

    @Mock
    private ServiceRegistryProperties properties;
    @Mock
    private ServiceClientRepository repository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private ServiceClientBootstrap bootstrap;

    private static ServiceRegistryProperties.Client definition(String clientId, String secret, String audiences) {
        ServiceRegistryProperties.Client client = new ServiceRegistryProperties.Client();
        client.setClientId(clientId);
        client.setSecret(secret);
        client.setAllowedAudiences(audiences);
        return client;
    }

    private static ServiceClient stored(String clientId, String audiences) {
        ServiceClient client = new ServiceClient();
        client.setClientId(clientId);
        client.setSecretHash(HASHED_SECRET);
        client.setAllowedAudiences(audiences);
        client.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return client;
    }

    private void configured(ServiceRegistryProperties.Client... clients) {
        when(properties.getClients()).thenReturn(List.of(clients));
    }

    private ServiceClient capturedSave() {
        ArgumentCaptor<ServiceClient> captor = ArgumentCaptor.forClass(ServiceClient.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("registering a client that is not in the database yet")
    class NewClient {

        @Test
        void storesOnlyABcryptHashOfTheConfiguredSecret() {
            configured(definition(BOOKING, RAW_SECRET, AUDIENCES));
            when(repository.findById(BOOKING)).thenReturn(Optional.empty());
            when(passwordEncoder.encode(RAW_SECRET)).thenReturn(HASHED_SECRET);

            bootstrap.provision();

            ServiceClient saved = capturedSave();
            assertThat(saved.getClientId()).isEqualTo(BOOKING);
            assertThat(saved.getSecretHash()).isEqualTo(HASHED_SECRET);
            // The plaintext credential from the deploy config is never persisted.
            assertThat(saved.getSecretHash()).isNotEqualTo(RAW_SECRET);
            assertThat(saved.getAllowedAudiences()).isEqualTo(AUDIENCES);
            assertThat(saved.isEnabled()).isTrue();
        }

        @Test
        void registersEveryConfiguredClient() {
            configured(definition(BOOKING, RAW_SECRET, AUDIENCES),
                    definition("payment-service", "payment-secret", "booking-service"));
            when(repository.findById(anyString())).thenReturn(Optional.empty());
            when(passwordEncoder.encode(anyString())).thenReturn(HASHED_SECRET);

            bootstrap.provision();

            verify(repository, times(2)).save(any(ServiceClient.class));
        }
    }

    @Nested
    @DisplayName("reconciling a client that already exists")
    class ExistingClient {

        @Test
        void syncsTheAudiencesWithoutRehashingTheStoredSecret() {
            // Re-hashing on every boot would invalidate nothing but would churn
            // the row; the credential itself is not in the config's gift to change.
            configured(definition(BOOKING, RAW_SECRET, "inventory-service"));
            ServiceClient existing = stored(BOOKING, AUDIENCES);
            when(repository.findById(BOOKING)).thenReturn(Optional.of(existing));

            bootstrap.provision();

            ServiceClient saved = capturedSave();
            assertThat(saved.getAllowedAudiences()).isEqualTo("inventory-service");
            assertThat(saved.getSecretHash()).isEqualTo(HASHED_SECRET);
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        void stampsTheUpdateTimeWhenAudiencesActuallyChange() {
            configured(definition(BOOKING, RAW_SECRET, "inventory-service"));
            ServiceClient existing = stored(BOOKING, AUDIENCES);
            LocalDateTime before = existing.getUpdatedAt();
            when(repository.findById(BOOKING)).thenReturn(Optional.of(existing));

            bootstrap.provision();

            assertThat(capturedSave().getUpdatedAt()).isAfter(before);
        }

        @Test
        void writesNothingWhenTheStoredAudiencesAlreadyMatch() {
            configured(definition(BOOKING, RAW_SECRET, AUDIENCES));
            when(repository.findById(BOOKING)).thenReturn(Optional.of(stored(BOOKING, AUDIENCES)));

            bootstrap.provision();

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the config is authoritative, so removal revokes")
    class Reconciliation {

        @Test
        void deprovisionsAClientThatIsNoLongerConfigured() {
            // A retired service would otherwise keep working credentials forever.
            configured(definition(BOOKING, RAW_SECRET, AUDIENCES));
            when(repository.findById(BOOKING)).thenReturn(Optional.of(stored(BOOKING, AUDIENCES)));
            ServiceClient retired = stored("legacy-service", "flight-service");
            when(repository.findAll()).thenReturn(List.of(stored(BOOKING, AUDIENCES), retired));

            bootstrap.provision();

            verify(repository).delete(retired);
        }

        @Test
        void keepsEveryClientThatIsStillConfigured() {
            configured(definition(BOOKING, RAW_SECRET, AUDIENCES));
            when(repository.findById(BOOKING)).thenReturn(Optional.of(stored(BOOKING, AUDIENCES)));
            when(repository.findAll()).thenReturn(List.of(stored(BOOKING, AUDIENCES)));

            bootstrap.provision();

            verify(repository, never()).delete(any());
        }

        @Test
        void clearsTheWholeRegistryWhenNoClientsAreConfigured() {
            configured();
            ServiceClient retired = stored(BOOKING, AUDIENCES);
            when(repository.findAll()).thenReturn(List.of(retired));

            bootstrap.provision();

            verify(repository).delete(retired);
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("malformed configuration entries")
    class MalformedEntries {

        @Test
        void skipsAnEntryWithNoClientId() {
            configured(definition(null, RAW_SECRET, AUDIENCES));

            bootstrap.provision();

            verify(repository, never()).findById(anyString());
            verify(repository, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        void skipsAnEntryWhoseClientIdIsBlank() {
            configured(definition("   ", RAW_SECRET, AUDIENCES));

            bootstrap.provision();

            verify(repository, never()).save(any());
        }

        @Test
        void doesNotLetABlankEntryProtectOtherwiseRetiredClients() {
            // A skipped entry must not count as "configured" for reconciliation,
            // or a typo'd clientId would keep a revoked client alive.
            configured(definition("  ", RAW_SECRET, AUDIENCES));
            ServiceClient retired = stored(BOOKING, AUDIENCES);
            when(repository.findAll()).thenReturn(List.of(retired));

            bootstrap.provision();

            verify(repository).delete(retired);
        }
    }
}
