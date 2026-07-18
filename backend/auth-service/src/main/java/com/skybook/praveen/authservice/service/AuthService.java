package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.LoginRequest;
import com.skybook.praveen.authservice.dto.RegisterRequest;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.producer.EmailEventProducer;
import com.skybook.praveen.authservice.repository.UserRepository;
import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.common.event.EmailType;
import lombok.RequiredArgsConstructor;
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

    public String register(RegisterRequest request) {

        // Normalize before every lookup/insert (SECURITY_HARDENING_MODULE.md §6)
        // so Alice@X.com and alice@x.com are one account; the DB CHECK enforces
        // it at the storage layer too.
        String email = normalize(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException("Email already registered");
        }

        User user = new User();
        user.setFullName(request.fullName());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setRole(UserRole.USER);

        User savedUser = userRepository.save(user);

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

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        boolean passwordMatches = passwordEncoder.matches(request.password(), user.getPassword());
        if (!passwordMatches) {
            throw new RuntimeException("Invalid email or password");
        }

        return jwtService.generateToken(user.getEmail(), user.getRole());
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
