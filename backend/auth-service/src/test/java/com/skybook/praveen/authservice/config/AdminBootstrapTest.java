package com.skybook.praveen.authservice.config;

import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Startup admin promotion (SECURITY_HARDENING_MODULE.md §4.3). The one thing it
 * must never do is manufacture privilege: it can only raise an account a human
 * already registered, and only the account named in the deploy configuration.
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    private static final String ADMIN_EMAIL = "root@skybook.com";

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminBootstrap adminBootstrap;

    private void configuredAdminEmail(String email) {
        ReflectionTestUtils.setField(adminBootstrap, "bootstrapAdminEmail", email);
    }

    private static User user(UserRole role) {
        User user = new User();
        user.setId(1L);
        user.setEmail(ADMIN_EMAIL);
        user.setRole(role);
        return user;
    }

    @Nested
    @DisplayName("promoting the configured account")
    class Promotion {

        @Test
        void raisesAnExistingUserToAdmin() {
            configuredAdminEmail(ADMIN_EMAIL);
            User existing = user(UserRole.USER);
            when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(existing));

            adminBootstrap.promoteBootstrapAdmin();

            assertThat(existing.getRole()).isEqualTo(UserRole.ADMIN);
            verify(userRepository).save(existing);
        }

        @Test
        void writesNothingWhenTheAccountIsAlreadyAdmin() {
            // Runs on every boot, so a no-op second run must not churn the row.
            configuredAdminEmail(ADMIN_EMAIL);
            when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user(UserRole.ADMIN)));

            adminBootstrap.promoteBootstrapAdmin();

            verify(userRepository, never()).save(any());
        }

        @Test
        void normalizesTheConfiguredEmailTheSameWayRegistrationDoes() {
            configuredAdminEmail("  Root@SkyBook.COM  ");
            when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user(UserRole.USER)));

            adminBootstrap.promoteBootstrapAdmin();

            verify(userRepository).findByEmail(ADMIN_EMAIL);
        }

        @Test
        void refusesToInventAnAccountForAnEmailThatWasNeverRegistered() {
            // Creating the account here would turn a typo in a deploy variable
            // into an administrator nobody signed up for.
            configuredAdminEmail("nobody@skybook.com");
            when(userRepository.findByEmail("nobody@skybook.com")).thenReturn(Optional.empty());

            adminBootstrap.promoteBootstrapAdmin();

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("when no bootstrap account is configured")
    class NotConfigured {

        @Test
        void promotesNobodyWhenThePropertyIsUnset() {
            configuredAdminEmail("");

            adminBootstrap.promoteBootstrapAdmin();

            verify(userRepository, never()).findByEmail(anyString());
            verify(userRepository, never()).save(any());
        }

        @Test
        void promotesNobodyWhenThePropertyIsOnlyWhitespace() {
            configuredAdminEmail("   ");

            adminBootstrap.promoteBootstrapAdmin();

            verify(userRepository, never()).findByEmail(anyString());
        }
    }

    @Nested
    @DisplayName("the standing check that somebody can administer the system")
    class AdminPresenceCheck {

        @Test
        void asksWhetherAnyAdministratorExistsEvenWithNoBootstrapConfigured() {
            configuredAdminEmail("");

            adminBootstrap.promoteBootstrapAdmin();

            verify(userRepository).existsByRole(UserRole.ADMIN);
        }

        @Test
        void asksAgainAfterPromotingSoTheOutcomeIsReported() {
            configuredAdminEmail(ADMIN_EMAIL);
            when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user(UserRole.USER)));
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(true);

            adminBootstrap.promoteBootstrapAdmin();

            verify(userRepository).existsByRole(UserRole.ADMIN);
        }
    }
}
