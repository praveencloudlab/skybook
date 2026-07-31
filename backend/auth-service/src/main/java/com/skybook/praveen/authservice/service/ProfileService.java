package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.ChangePasswordRequest;
import com.skybook.praveen.authservice.dto.ProfileResponse;
import com.skybook.praveen.authservice.dto.SavedTravellerRequest;
import com.skybook.praveen.authservice.dto.SavedTravellerResponse;
import com.skybook.praveen.authservice.dto.UpdateProfileRequest;
import com.skybook.praveen.authservice.entity.SavedTraveller;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.exception.IncorrectCurrentPasswordException;
import com.skybook.praveen.authservice.exception.InvalidCredentialsException;
import com.skybook.praveen.authservice.repository.SavedTravellerRepository;
import com.skybook.praveen.authservice.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Passenger profile + saved travellers (FRONTEND_MODULE.md Module 14).
 *
 * <p>Everything is scoped to the caller's own account: the email comes from the
 * validated token (never a request field), and saved-traveller lookups are
 * ownership-scoped by {@code userId}, so one user can neither read nor mutate
 * another's data even by guessing an id.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final SavedTravellerRepository savedTravellerRepository;
    private final PasswordEncoder passwordEncoder;

    public ProfileResponse getProfile(String email) {
        return toProfile(requireUser(email));
    }

    @Transactional
    public ProfileResponse updateProfile(String email, UpdateProfileRequest request) {
        User user = requireUser(email);
        // Null means "not provided" - only overwrite fields the client sent, so a
        // partial save doesn't blank the rest. fullName, if blank, is left alone.
        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName().trim());
        }
        if (request.phone() != null) user.setPhone(blankToNull(request.phone()));
        if (request.dateOfBirth() != null) user.setDateOfBirth(request.dateOfBirth());
        if (request.nationality() != null) user.setNationality(upperOrNull(request.nationality()));
        if (request.passportNumber() != null) user.setPassportNumber(blankToNull(request.passportNumber()));
        if (request.passportExpiry() != null) user.setPassportExpiry(request.passportExpiry());
        if (request.emergencyContactName() != null) user.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        if (request.emergencyContactPhone() != null) user.setEmergencyContactPhone(blankToNull(request.emergencyContactPhone()));
        if (request.preferredLanguage() != null) user.setPreferredLanguage(blankToNull(request.preferredLanguage()));
        if (request.preferredCurrency() != null) {
            String currency = blankToNull(request.preferredCurrency());
            user.setPreferredCurrency(currency != null ? currency.toUpperCase() : null);
        }
        return toProfile(userRepository.save(user));
    }

    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = requireUser(email);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new IncorrectCurrentPasswordException();
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public List<SavedTravellerResponse> listTravellers(String email) {
        Long userId = requireUser(email).getId();
        return savedTravellerRepository.findByUserIdOrderByFirstNameAscLastNameAsc(userId).stream()
                .map(ProfileService::toTraveller)
                .toList();
    }

    @Transactional
    public SavedTravellerResponse addTraveller(String email, SavedTravellerRequest request) {
        Long userId = requireUser(email).getId();
        SavedTraveller traveller = new SavedTraveller();
        traveller.setUserId(userId);
        apply(traveller, request);
        return toTraveller(savedTravellerRepository.save(traveller));
    }

    @Transactional
    public SavedTravellerResponse updateTraveller(String email, Long id, SavedTravellerRequest request) {
        Long userId = requireUser(email).getId();
        SavedTraveller traveller = savedTravellerRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new EntityNotFoundException("Traveller not found"));
        apply(traveller, request);
        return toTraveller(savedTravellerRepository.save(traveller));
    }

    @Transactional
    public void deleteTraveller(String email, Long id) {
        Long userId = requireUser(email).getId();
        SavedTraveller traveller = savedTravellerRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new EntityNotFoundException("Traveller not found"));
        savedTravellerRepository.delete(traveller);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(normalize(email))
                // The token was valid, so the user must exist; a miss here is a
                // real inconsistency, treated as a failed credential.
                .orElseThrow(InvalidCredentialsException::new);
    }

    private static void apply(SavedTraveller traveller, SavedTravellerRequest request) {
        traveller.setTitle(blankToNull(request.title()));
        traveller.setFirstName(request.firstName().trim());
        traveller.setLastName(request.lastName().trim());
        traveller.setDateOfBirth(request.dateOfBirth());
        traveller.setNationality(upperOrNull(request.nationality()));
        traveller.setPassportNumber(blankToNull(request.passportNumber()));
        traveller.setPassportExpiry(request.passportExpiry());
    }

    private static ProfileResponse toProfile(User user) {
        return new ProfileResponse(
                user.getEmail(), user.getFullName(), user.getRole().name(),
                user.getPhone(), user.getDateOfBirth(), user.getNationality(),
                user.getPassportNumber(), user.getPassportExpiry(),
                user.getEmergencyContactName(), user.getEmergencyContactPhone(),
                user.getPreferredLanguage(), user.getPreferredCurrency());
    }

    private static SavedTravellerResponse toTraveller(SavedTraveller t) {
        return new SavedTravellerResponse(
                t.getId(), t.getTitle(), t.getFirstName(), t.getLastName(),
                t.getDateOfBirth(), t.getNationality(), t.getPassportNumber(), t.getPassportExpiry());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upperOrNull(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
