package com.skybook.praveen.checkinservice.entity;

import com.skybook.praveen.checkinservice.enums.ManifestStatus;
import com.skybook.praveen.common.entity.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row per flight (design doc section 3.5) - a report over CheckIn rows,
 * not their container (CheckIn does not carry a manifest FK; a flight has
 * checked-in passengers well before anyone finalizes anything). While
 * status is OPEN, counters below are ignored - GET /api/manifests/{flightId}
 * computes them live via query. They are frozen and become authoritative
 * only once FINALIZED, which is terminal (read-only, section 5.7).
 */
@Entity
@Table(name = "flight_manifests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightManifest extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_id", nullable = false, unique = true, updatable = false)
    private Long flightId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ManifestStatus status;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "checked_in_count")
    private Integer checkedInCount;

    @Column(name = "boarded_count")
    private Integer boardedCount;

    @Column(name = "no_show_count")
    private Integer noShowCount;

    @Column(name = "baggage_count")
    private Integer baggageCount;

    @Column(name = "baggage_weight_kg")
    private BigDecimal baggageWeightKg;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = ManifestStatus.OPEN;
        }
        if (checkedInCount == null) {
            checkedInCount = 0;
        }
        if (boardedCount == null) {
            boardedCount = 0;
        }
        if (noShowCount == null) {
            noShowCount = 0;
        }
        if (baggageCount == null) {
            baggageCount = 0;
        }
        if (baggageWeightKg == null) {
            baggageWeightKg = BigDecimal.ZERO;
        }
    }
}
