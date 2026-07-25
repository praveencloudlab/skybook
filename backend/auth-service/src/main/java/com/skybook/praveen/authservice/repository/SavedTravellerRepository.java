package com.skybook.praveen.authservice.repository;

import com.skybook.praveen.authservice.entity.SavedTraveller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedTravellerRepository extends JpaRepository<SavedTraveller, Long> {

    List<SavedTraveller> findByUserIdOrderByFirstNameAscLastNameAsc(Long userId);

    /** Ownership-scoped lookup: an id alone is never enough to reach a row. */
    Optional<SavedTraveller> findByIdAndUserId(Long id, Long userId);
}
