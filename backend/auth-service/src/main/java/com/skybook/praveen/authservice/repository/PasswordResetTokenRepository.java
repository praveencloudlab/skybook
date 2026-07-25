package com.skybook.praveen.authservice.repository;

import com.skybook.praveen.authservice.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Invalidate every outstanding token for a user before issuing a new one, so
     * a fresh "forgot password" request silently voids any earlier link.
     */
    @Transactional
    void deleteByUserId(Long userId);
}
