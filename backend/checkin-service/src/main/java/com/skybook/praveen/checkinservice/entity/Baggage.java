package com.skybook.praveen.checkinservice.entity;

import com.skybook.praveen.common.entity.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One checked bag (design doc section 3.3/5.5) - only addable while the
 * owning CheckIn is CHECKED_IN. excess/excessCharge are computed once at
 * add-time by BaggageAllowanceCalculator against the passenger's
 * travelClass/fareType and are not recomputed retroactively if allowance
 * config changes later.
 */
@Entity
@Table(name = "baggage", indexes = {
        @Index(name = "ix_baggage_checkin", columnList = "check_in_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Baggage extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_in_id", nullable = false, updatable = false)
    private CheckIn checkIn;

    // System-generated, unique.
    @Column(name = "tag_number", nullable = false, unique = true, updatable = false, length = 20)
    private String tagNumber;

    @Column(name = "weight_kg", nullable = false, updatable = false)
    private BigDecimal weightKg;

    @Column(nullable = false, updatable = false)
    private boolean excess;

    // Populated when excess is true. Informational only in v1 - not wired
    // to payment-service (design doc section 5.5/9.4/14).
    @Column(name = "excess_charge", updatable = false)
    private BigDecimal excessCharge;
}
