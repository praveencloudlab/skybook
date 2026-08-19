package com.skybook.praveen.authservice.sso;

import com.skybook.praveen.authservice.entity.FederatedIdentity;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.producer.EmailEventProducer;
import com.skybook.praveen.authservice.repository.FederatedIdentityRepository;
import com.skybook.praveen.authservice.repository.UserRepository;
import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.common.event.EmailType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The frozen callback decision tree (SSO_MODULE.md §4.2), branch by branch.
 * The property that carries the weight: identity is keyed on the provider's
 * stable subject, and email only ever decides things when Google has vouched
 * for it.
 */
@ExtendWith(MockitoExtension.class)
class SsoAccountServiceTest {

    private static final String SUB = "google-sub-1108";
    private static final String EMAIL = "bob@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private FederatedIdentityRepository federatedIdentityRepository;
    @Mock
    private EmailEventProducer emailEventProducer;

    @InjectMocks
    private SsoAccountService service;

    private static User bob(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail(EMAIL);
        user.setFullName("Bob Jones");
        user.setRole(UserRole.USER);
        // The steady state of an established account (V9 grandfathers every
        // pre-feature row); the unverified branch has its own test below.
        user.setEmailVerified(true);
        return user;
    }

    private static FederatedIdentity linkTo(Long userId) {
        FederatedIdentity identity = new FederatedIdentity();
        identity.setId(3L);
        identity.setUserId(userId);
        identity.setProvider("google");
        identity.setSubject(SUB);
        identity.setEmailAtLink(EMAIL);
        return identity;
    }

    @Nested
    @DisplayName("branch 1 - the stable key wins")
    class BySubject {

        @Test
        void anAlreadyLinkedIdentitySignsInBySubjectAlone() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.of(linkTo(7L)));
            when(userRepository.findById(7L)).thenReturn(Optional.of(bob(7L)));

            User resolved = service.resolve(SUB, EMAIL, true, "Bob Jones");

            assertThat(resolved.getId()).isEqualTo(7L);
            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        void aChangedGoogleEmailDoesNotMoveTheAccount() {
            // Identity is sub; email is contact info. Google now reports a new
            // address - the SkyBook account and its email stay put.
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.of(linkTo(7L)));
            when(userRepository.findById(7L)).thenReturn(Optional.of(bob(7L)));

            User resolved = service.resolve(SUB, "renamed@example.com", true, "Bob Jones");

            assertThat(resolved.getEmail()).isEqualTo(EMAIL);
        }

        @Test
        void anUnverifiedEmailDoesNotBlockAnAlreadyLinkedIdentity() {
            // Verification gates TRUST DECISIONS about the email (linking,
            // provisioning). A returning identity made its trust decision at
            // link time; sub alone signs it in.
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.of(linkTo(7L)));
            when(userRepository.findById(7L)).thenReturn(Optional.of(bob(7L)));

            assertThat(service.resolve(SUB, EMAIL, false, "Bob Jones").getId()).isEqualTo(7L);
        }
    }

    @Nested
    @DisplayName("branch 2 - unverified email proves nothing")
    class Unverified {

        @Test
        void rejectsANewIdentityWithAnUnverifiedEmail() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(SUB, EMAIL, false, "Bob Jones"))
                    .isInstanceOf(SsoEmailUnverifiedException.class);

            verify(userRepository, never()).findByEmail(any());
            verify(userRepository, never()).save(any());
        }

        @Test
        void aMissingEmailClaimCountsAsUnverified() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(SUB, null, true, "Bob Jones"))
                    .isInstanceOf(SsoEmailUnverifiedException.class);
        }
    }

    @Nested
    @DisplayName("branch 3 - link to the existing account by verified email")
    class Link {

        @Test
        void linksAndSignsInWhenTheNormalizedEmailMatches() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(bob(7L)));

            User resolved = service.resolve(SUB, "  Bob@Example.COM  ", true, "Bob Jones");

            assertThat(resolved.getId()).isEqualTo(7L);
            ArgumentCaptor<FederatedIdentity> saved = ArgumentCaptor.forClass(FederatedIdentity.class);
            verify(federatedIdentityRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(7L);
            assertThat(saved.getValue().getSubject()).isEqualTo(SUB);
            assertThat(saved.getValue().getEmailAtLink()).isEqualTo(EMAIL);
            // Linking is not registering - no welcome email for an account
            // that already exists.
            verifyNoInteractions(emailEventProducer);
        }

        @Test
        void googleVouchingForTheAddressCompletesAPendingOtpVerification() {
            // A password account still waiting on its emailed code: Google just
            // verified the same address, which is the proof the OTP exists to
            // obtain - so the account comes out verified and password login is
            // unblocked too.
            User pending = bob(7L);
            pending.setEmailVerified(false);
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            User resolved = service.resolve(SUB, EMAIL, true, "Bob Jones");

            assertThat(resolved.isEmailVerified()).isTrue();
            verify(userRepository).save(pending);
        }
    }

    @Nested
    @DisplayName("branch 4 - provision a Google-only account")
    class Provision {

        @Test
        void provisionsWithoutAPasswordAndWelcomesTheNewAccount() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> {
                User toSave = inv.getArgument(0);
                toSave.setId(41L);
                return toSave;
            });

            User resolved = service.resolve(SUB, EMAIL, true, "Bob Jones");

            assertThat(resolved.getId()).isEqualTo(41L);
            assertThat(resolved.getPassword()).isNull();
            assertThat(resolved.getRole()).isEqualTo(UserRole.USER);
            assertThat(resolved.getFullName()).isEqualTo("Bob Jones");

            ArgumentCaptor<EmailEvent> welcome = ArgumentCaptor.forClass(EmailEvent.class);
            verify(emailEventProducer).sendEmailEvent(welcome.capture());
            assertThat(welcome.getValue().getType()).isEqualTo(EmailType.REGISTRATION_SUCCESS);
            assertThat(welcome.getValue().getTo()).isEqualTo(EMAIL);
        }

        @Test
        void fallsBackToTheEmailLocalPartWhenGoogleOmitsTheName() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.resolve(SUB, EMAIL, true, null).getFullName()).isEqualTo("bob");
        }
    }

    @Nested
    @DisplayName("the races translate, never explode")
    class Races {

        @Test
        void aConcurrentProvisionOfTheSameEmailAdoptsTheWinner() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(EMAIL))
                    .thenReturn(Optional.empty())          // the pre-check misses
                    .thenReturn(Optional.of(bob(77L)));    // the re-lookup finds the winner
            when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("users_email_key"));

            User resolved = service.resolve(SUB, EMAIL, true, "Bob Jones");

            assertThat(resolved.getId()).isEqualTo(77L);
        }

        @Test
        void aConcurrentLinkOfTheSameIdentityAdoptsTheWinnersUser() {
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty())                    // first look: not linked
                    .thenReturn(Optional.of(linkTo(99L)));           // after the race: the winner's link
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(bob(7L)));
            when(federatedIdentityRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("uq_provider_subject"));
            when(userRepository.findById(99L)).thenReturn(Optional.of(bob(99L)));

            assertThat(service.resolve(SUB, EMAIL, true, "Bob Jones").getId()).isEqualTo(99L);
        }

        @Test
        void anAccountAlreadyLinkedToADifferentGoogleIdentityStillSignsIn() {
            // uq_user_provider tripped: the email matched but this SkyBook
            // account carries a DIFFERENT google sub. The old link stands; the
            // verified email match still signs the caller in.
            when(federatedIdentityRepository.findByProviderAndSubject("google", SUB))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());                   // this sub never got linked
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(bob(7L)));
            when(federatedIdentityRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("uq_user_provider"));

            assertThat(service.resolve(SUB, EMAIL, true, "Bob Jones").getId()).isEqualTo(7L);
        }
    }
}
