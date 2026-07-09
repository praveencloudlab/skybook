package com.skybook.praveen.checkinservice.repository;

import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {

    Optional<CheckIn> findByBookingPassengerId(Long bookingPassengerId);

    boolean existsByBookingPassengerId(Long bookingPassengerId);

    List<CheckIn> findByBookingId(Long bookingId);

    List<CheckIn> findByFlightId(Long flightId);

    // No-show sweep (design doc section 5.7/10): rows still in a
    // pre-boarding status once their flight's departure has passed the
    // configured gate-close cutoff.
    List<CheckIn> findByStatusInAndDepartureTimeBefore(Collection<CheckInStatus> statuses, LocalDateTime cutoff);

    /**
     * Manifest finalization sweep (design doc section 5.7/10): every flight
     * with at least one CheckIn whose gate has closed, regardless of that
     * CheckIn's own status - a flight is finalizable even if every
     * passenger ended up CANCELLED/NO_SHOW.
     *
     * Explicit @Query rather than a derived "findDistinctFlightIdBy..."
     * method name - Spring Data does not treat the property between
     * "findDistinct" and "By" as a projection selector the way it looks
     * like it should; without this it silently parses into a full-entity
     * "SELECT DISTINCT c FROM CheckIn c" query instead, which fails at
     * result-binding time against a List&lt;Long&gt; return type. Found by the
     * JPA/Testcontainers test, not by inspection - the derived method name
     * still compiles and passes Spring Data's startup validation either way.
     */
    @Query("select distinct c.flightId from CheckIn c where c.departureTime < :cutoff")
    List<Long> findDistinctFlightIdByDepartureTimeBefore(@Param("cutoff") LocalDateTime cutoff);
}
