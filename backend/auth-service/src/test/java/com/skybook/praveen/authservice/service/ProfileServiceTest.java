package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.ChangePasswordRequest;
import com.skybook.praveen.authservice.dto.ProfileResponse;
import com.skybook.praveen.authservice.dto.SavedTravellerRequest;
import com.skybook.praveen.authservice.dto.SavedTravellerResponse;
import com.skybook.praveen.authservice.dto.UpdateProfileRequest;
import com.skybook.praveen.authservice.entity.SavedTraveller;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.exception.IncorrectCurrentPasswordException;
import com.skybook.praveen.authservice.exception.InvalidCredentialsException;
import com.skybook.praveen.authservice.repository.SavedTravellerRepository;
import com.skybook.praveen.authservice.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Passenger profile + saved travellers (FRONTEND_MODULE.md Module 14). The
 * account is always resolved from the token subject, and every traveller lookup
 * is ownership-scoped - so the cases that matter most here are the ones where a
 * caller reaches for an id that is not theirs.
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final String ALICE = "alice@example.com";
    private static final long ALICE_ID = 7L;
    private static final String STORED_HASH = "$2a$10$stored-hash-for-alice";

    @Mock
    private UserRepository userRepository;
    @Mock
    private SavedTravellerRepository savedTravellerRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private ProfileService profileService;

    private static User alice() {
        User user = new User();
        user.setId(ALICE_ID);
        user.setEmail(ALICE);
        user.setFullName("Alice Smith");
        user.setPassword(STORED_HASH);
        user.setRole(UserRole.USER);
        return user;
    }

    private static SavedTraveller traveller(long id, String first, String last) {
        SavedTraveller t = new SavedTraveller();
        t.setId(id);
        t.setUserId(ALICE_ID);
        t.setFirstName(first);
        t.setLastName(last);
        return t;
    }

    private static SavedTravellerRequest travellerRequest() {
        return new SavedTravellerRequest("Mr", "  Bob  ", "  Brown  ",
                LocalDate.now().minusYears(30), "gbr", " X1234567 ",
                LocalDate.now().plusYears(5));
    }

    private User expectAliceIsFound() {
        User alice = alice();
        when(userRepository.findByEmail(ALICE)).thenReturn(Optional.of(alice));
        return alice;
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the account always comes from the token subject")
    class AccountResolution {

        @Test
        void returnsTheProfileOfTheSignedInAccount() {
            User alice = expectAliceIsFound();
            alice.setPhone("+44 7700 900000");
            alice.setNationality("GBR");
            alice.setPreferredCurrency("GBP");

            ProfileResponse profile = profileService.getProfile(ALICE);

            assertThat(profile.email()).isEqualTo(ALICE);
            assertThat(profile.fullName()).isEqualTo("Alice Smith");
            assertThat(profile.role()).isEqualTo("USER");
            assertThat(profile.phone()).isEqualTo("+44 7700 900000");
            assertThat(profile.nationality()).isEqualTo("GBR");
            assertThat(profile.preferredCurrency()).isEqualTo("GBP");
        }

        @Test
        void normalizesTheSubjectTheSameWayRegistrationDid() {
            // A token minted before normalization could still carry mixed case.
            expectAliceIsFound();

            profileService.getProfile("  Alice@Example.COM  ");

            verify(userRepository).findByEmail(ALICE);
        }

        @Test
        void refusesAValidTokenWhoseAccountNoLongerExists() {
            when(userRepository.findByEmail(ALICE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> profileService.getProfile(ALICE))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("updateProfile edits only the fields the client sent")
    class UpdateProfile {

        private UpdateProfileRequest onlyPhone(String phone) {
            return new UpdateProfileRequest(null, phone, null, null, null, null, null, null, null, null);
        }

        @Test
        void leavesUnsentFieldsUntouchedSoAPartialSaveDoesNotBlankTheRest() {
            User alice = expectAliceIsFound();
            alice.setNationality("GBR");
            alice.setPassportNumber("X1234567");
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            ProfileResponse updated = profileService.updateProfile(ALICE, onlyPhone("+44 7700 900111"));

            assertThat(updated.phone()).isEqualTo("+44 7700 900111");
            assertThat(updated.nationality()).isEqualTo("GBR");
            assertThat(updated.passportNumber()).isEqualTo("X1234567");
            assertThat(updated.fullName()).isEqualTo("Alice Smith");
        }

        @Test
        void keepsTheExistingNameWhenABlankOneIsSent() {
            expectAliceIsFound();
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            ProfileResponse updated = profileService.updateProfile(ALICE,
                    new UpdateProfileRequest("   ", null, null, null, null, null, null, null, null, null));

            assertThat(updated.fullName()).isEqualTo("Alice Smith");
        }

        @Test
        void clearsAFieldWhenAnEmptyStringIsSent() {
            User alice = expectAliceIsFound();
            alice.setPhone("+44 7700 900000");
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(profileService.updateProfile(ALICE, onlyPhone("")).phone()).isNull();
        }

        @Test
        void normalizesCodesToUpperCaseAndTrimsWhitespace() {
            expectAliceIsFound();
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            ProfileResponse updated = profileService.updateProfile(ALICE,
                    new UpdateProfileRequest("  Alice Smythe  ", null, null, " gbr ", null, null,
                            null, null, "en", "gbp"));

            assertThat(updated.fullName()).isEqualTo("Alice Smythe");
            assertThat(updated.nationality()).isEqualTo("GBR");
            assertThat(updated.preferredCurrency()).isEqualTo("GBP");
            assertThat(updated.preferredLanguage()).isEqualTo("en");
        }

        @Test
        void storesTheTravelDocumentDatesItWasGiven() {
            expectAliceIsFound();
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
            LocalDate birthday = LocalDate.now().minusYears(34);
            LocalDate expiry = LocalDate.now().plusYears(7);

            ProfileResponse updated = profileService.updateProfile(ALICE,
                    new UpdateProfileRequest(null, null, birthday, null, "X1234567", expiry,
                            "Bob Brown", "+44 7700 900222", null, null));

            assertThat(updated.dateOfBirth()).isEqualTo(birthday);
            assertThat(updated.passportExpiry()).isEqualTo(expiry);
            assertThat(updated.emergencyContactName()).isEqualTo("Bob Brown");
            assertThat(updated.emergencyContactPhone()).isEqualTo("+44 7700 900222");
        }

        @Test
        void neverExposesThePasswordHashInTheProfileItReturns() {
            expectAliceIsFound();
            when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(profileService.updateProfile(ALICE, onlyPhone("+44 7700 900111")).toString())
                    .doesNotContain(STORED_HASH);
        }
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("changePassword requires the current password")
    class ChangePassword {

        @Test
        void replacesTheHashOnlyAfterTheCurrentPasswordVerifies() {
            expectAliceIsFound();
            when(passwordEncoder.matches("OldPass123!", STORED_HASH)).thenReturn(true);
            when(passwordEncoder.encode("NewPass456!")).thenReturn("$2a$10$brand-new-hash");

            profileService.changePassword(ALICE, new ChangePasswordRequest("OldPass123!", "NewPass456!"));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getPassword()).isEqualTo("$2a$10$brand-new-hash");
            assertThat(saved.getValue().getPassword()).isNotEqualTo("NewPass456!");
        }

        @Test
        void refusesTheChangeWhenTheCurrentPasswordIsWrong() {
            // A stolen session must not be enough to take the account over.
            expectAliceIsFound();
            when(passwordEncoder.matches("guessed", STORED_HASH)).thenReturn(false);

            assertThatThrownBy(() -> profileService.changePassword(ALICE,
                    new ChangePasswordRequest("guessed", "NewPass456!")))
                    .isInstanceOf(IncorrectCurrentPasswordException.class);

            verify(userRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(any());
        }
    }

    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("saved travellers are scoped to their owner")
    class SavedTravellers {

        @Test
        void listsOnlyTheCallersOwnTravellers() {
            expectAliceIsFound();
            when(savedTravellerRepository.findByUserIdOrderByFirstNameAscLastNameAsc(ALICE_ID))
                    .thenReturn(List.of(traveller(1L, "Bob", "Brown"), traveller(2L, "Cara", "Clark")));

            List<SavedTravellerResponse> travellers = profileService.listTravellers(ALICE);

            assertThat(travellers).extracting(SavedTravellerResponse::firstName)
                    .containsExactly("Bob", "Cara");
            // The query is keyed on the owner, never on an unfiltered "find all".
            verify(savedTravellerRepository).findByUserIdOrderByFirstNameAscLastNameAsc(ALICE_ID);
        }

        @Test
        void returnsAnEmptyListRatherThanNullWhenNoneAreSaved() {
            expectAliceIsFound();
            when(savedTravellerRepository.findByUserIdOrderByFirstNameAscLastNameAsc(ALICE_ID))
                    .thenReturn(List.of());

            assertThat(profileService.listTravellers(ALICE)).isEmpty();
        }

        @Test
        void stampsANewTravellerWithTheCallersOwnUserId() {
            expectAliceIsFound();
            when(savedTravellerRepository.save(any(SavedTraveller.class)))
                    .thenAnswer(call -> call.getArgument(0));

            SavedTravellerResponse created = profileService.addTraveller(ALICE, travellerRequest());

            ArgumentCaptor<SavedTraveller> saved = ArgumentCaptor.forClass(SavedTraveller.class);
            verify(savedTravellerRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(ALICE_ID);
            // Trimmed on the way in, and the country code upper-cased.
            assertThat(created.firstName()).isEqualTo("Bob");
            assertThat(created.lastName()).isEqualTo("Brown");
            assertThat(created.nationality()).isEqualTo("GBR");
            assertThat(created.passportNumber()).isEqualTo("X1234567");
        }

        @Test
        void updatesATravellerOnlyThroughAnOwnershipScopedLookup() {
            expectAliceIsFound();
            when(savedTravellerRepository.findByIdAndUserId(3L, ALICE_ID))
                    .thenReturn(Optional.of(traveller(3L, "Old", "Name")));
            when(savedTravellerRepository.save(any(SavedTraveller.class)))
                    .thenAnswer(call -> call.getArgument(0));

            SavedTravellerResponse updated = profileService.updateTraveller(ALICE, 3L, travellerRequest());

            assertThat(updated.firstName()).isEqualTo("Bob");
            // An id alone is never enough to reach a row.
            verify(savedTravellerRepository).findByIdAndUserId(3L, ALICE_ID);
        }

        @Test
        void refusesToUpdateATravellerBelongingToSomeoneElse() {
            expectAliceIsFound();
            // The ownership-scoped query simply does not see another user's row.
            when(savedTravellerRepository.findByIdAndUserId(99L, ALICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> profileService.updateTraveller(ALICE, 99L, travellerRequest()))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(savedTravellerRepository, never()).save(any());
        }

        @Test
        void deletesOnlyATravellerTheCallerOwns() {
            expectAliceIsFound();
            SavedTraveller bob = traveller(3L, "Bob", "Brown");
            when(savedTravellerRepository.findByIdAndUserId(3L, ALICE_ID)).thenReturn(Optional.of(bob));

            profileService.deleteTraveller(ALICE, 3L);

            verify(savedTravellerRepository).delete(bob);
        }

        @Test
        void refusesToDeleteATravellerBelongingToSomeoneElse() {
            expectAliceIsFound();
            when(savedTravellerRepository.findByIdAndUserId(99L, ALICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> profileService.deleteTraveller(ALICE, 99L))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(savedTravellerRepository, never()).delete(any());
        }

        @Test
        void keepsOptionalTravellerDetailsNullRatherThanBlank() {
            expectAliceIsFound();
            when(savedTravellerRepository.save(any(SavedTraveller.class)))
                    .thenAnswer(call -> call.getArgument(0));

            SavedTravellerResponse created = profileService.addTraveller(ALICE,
                    new SavedTravellerRequest("  ", "Bob", "Brown", null, "  ", "  ", null));

            assertThat(created.title()).isNull();
            assertThat(created.nationality()).isNull();
            assertThat(created.passportNumber()).isNull();
            assertThat(created.dateOfBirth()).isNull();
        }
    }
}
