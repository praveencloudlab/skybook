package com.skybook.praveen.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One requested boarding-pass email delivery (GUEST_CHECKIN_MODULE.md §5) -
 * the throttle's shared state and the abuse audit trail in one row. The
 * address is stored only as a SHA-256 hash: the log proves volume and
 * attribution without becoming a mailing list.
 */
@Entity
@Table(name = "boarding_pass_email_log")
@Getter
@Setter
public class BoardingPassEmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_in_id", nullable = false)
    private Long checkInId;

    @Column(name = "resend_id", nullable = false, length = 36, unique = true)
    private String resendId;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "address_hash", nullable = false, length = 64)
    private String addressHash;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();
}
