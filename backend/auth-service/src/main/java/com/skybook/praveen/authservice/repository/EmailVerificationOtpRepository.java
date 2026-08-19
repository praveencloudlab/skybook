package com.skybook.praveen.authservice.repository;

import com.skybook.praveen.authservice.entity.EmailVerificationOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationOtpRepository extends JpaRepository<EmailVerificationOtp, Long> {

    Optional<EmailVerificationOtp> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
