package com.skybook.praveen.authservice.controller;

import com.skybook.praveen.authservice.dto.ChangePasswordRequest;
import com.skybook.praveen.authservice.dto.ProfileResponse;
import com.skybook.praveen.authservice.dto.SavedTravellerRequest;
import com.skybook.praveen.authservice.dto.SavedTravellerResponse;
import com.skybook.praveen.authservice.dto.UpdateProfileRequest;
import com.skybook.praveen.authservice.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Passenger profile + saved travellers (FRONTEND_MODULE.md Module 14).
 *
 * <p>Everything hangs off {@code /api/profile} so a single gateway route covers
 * it, and every method is scoped to the caller: the account is resolved from
 * {@code authentication.getName()} (the token subject), never from a request
 * field, so there is no id a caller could substitute to reach someone else's
 * data. All paths require a valid session (the service's default authorization).
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ProfileResponse getProfile(Authentication auth) {
        return profileService.getProfile(auth.getName());
    }

    @PutMapping
    public ProfileResponse updateProfile(Authentication auth, @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.updateProfile(auth.getName(), request);
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(Authentication auth, @Valid @RequestBody ChangePasswordRequest request) {
        profileService.changePassword(auth.getName(), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/travellers")
    public List<SavedTravellerResponse> listTravellers(Authentication auth) {
        return profileService.listTravellers(auth.getName());
    }

    @PostMapping("/travellers")
    public ResponseEntity<SavedTravellerResponse> addTraveller(
            Authentication auth, @Valid @RequestBody SavedTravellerRequest request) {
        return ResponseEntity.status(201).body(profileService.addTraveller(auth.getName(), request));
    }

    @PutMapping("/travellers/{id}")
    public SavedTravellerResponse updateTraveller(
            Authentication auth, @PathVariable Long id, @Valid @RequestBody SavedTravellerRequest request) {
        return profileService.updateTraveller(auth.getName(), id, request);
    }

    @DeleteMapping("/travellers/{id}")
    public ResponseEntity<Void> deleteTraveller(Authentication auth, @PathVariable Long id) {
        profileService.deleteTraveller(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
