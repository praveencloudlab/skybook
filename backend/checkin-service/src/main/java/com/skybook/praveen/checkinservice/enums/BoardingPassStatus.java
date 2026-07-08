package com.skybook.praveen.checkinservice.enums;

/**
 * Plain vocabulary, no state machine - a boarding pass is issued once per
 * CheckIn and revoked at most once (reissue on seat change creates a new
 * pass and revokes the old one). See docs/CHECKIN_SERVICE_MODULE.md
 * section 6.
 */
public enum BoardingPassStatus {

    ACTIVE,

    REVOKED
}
