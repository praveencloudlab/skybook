package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.LoginRequest;
import com.skybook.praveen.authservice.dto.RegisterRequest;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.exception.EmailAlreadyRegisteredException;
import com.skybook.praveen.authservice.exception.InvalidCredentialsException;
import com.skybook.praveen.authservice.producer.EmailEventProducer;
import com.skybook.praveen.authservice.repository.UserRepository;
import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.common.event.EmailType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmailEventProducer emailEventProducer;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * A precomputed hash the login path compares against when the user does not
     * exist, so "unknown user" runs the same BCrypt work as "wrong password" and
     * the two stay timing-indistinguishable (no user enumeration, §6).
     */
    private String dummyPasswordHash;

    @PostConstruct
    void initConstantTimeHash() {
        this.dummyPasswordHash = passwordEncoder.encode("constant-time-placeholder");
    }

    public String register(RegisterRequest request) {

        // Normalize before every lookup/insert (SECURITY_HARDENING_MODULE.md §6)
        // so Alice@X.com and alice@x.com are one account; the DB CHECK enforces
        // it at the storage layer too.
        String email = normalize(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User();
        user.setFullName(request.fullName());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setRole(UserRole.USER);

        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Concurrent double-register: the existsByEmail pre-check passed but
            // the unique index rejected the insert. Translate the race to the
            // same generic 409 (§6).
            throw new EmailAlreadyRegisteredException();
        }

        EmailEvent emailEvent = EmailEvent.builder()
                .to(savedUser.getEmail())
                .subject("Welcome to SkyBook")
                .body("Hi " + savedUser.getFullName() + ", welcome to SkyBook!")
                .type(EmailType.REGISTRATION_SUCCESS)
                .build();

        emailEventProducer.sendEmailEvent(emailEvent);

        return "User registered successfully";
    }

    public String login(LoginRequest request) {

        String email = normalize(request.email());

        User user = userRepository.findByEmail(email).orElse(null);

        // Run BCrypt in both branches (real hash, or the dummy for a missing
        // user) so timing can't distinguish "no such user" from "wrong password".
        String hashToCheck = (user != null) ? user.getPassword() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        return jwtService.generateToken(user.getEmail(), user.getRole());
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
