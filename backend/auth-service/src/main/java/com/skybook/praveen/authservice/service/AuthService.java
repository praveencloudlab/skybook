package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.dto.LoginRequest;
import com.skybook.praveen.authservice.dto.RegisterRequest;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.producer.EmailEventProducer;
import com.skybook.praveen.authservice.repository.UserRepository;
import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.common.event.EmailType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmailEventProducer emailEventProducer;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public String register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new RuntimeException("Email already registered");
        }

        User user = new User();
        user.setFullName(request.fullName());
        user.setPassword(passwordEncoder.encode(request.password()));
       // user.setPassword(request.password());
        user.setEmail(request.email());

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

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                user.getPassword()
        );

        if (!passwordMatches) {
            throw new RuntimeException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getEmail());

        return token;
    }
}