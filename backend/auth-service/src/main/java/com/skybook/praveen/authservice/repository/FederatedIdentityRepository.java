package com.skybook.praveen.authservice.repository;

import com.skybook.praveen.authservice.entity.FederatedIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FederatedIdentityRepository extends JpaRepository<FederatedIdentity, Long> {

    /** The only lookup the callback decision tree starts from (SSO_MODULE.md §4.2). */
    Optional<FederatedIdentity> findByProviderAndSubject(String provider, String subject);
}
