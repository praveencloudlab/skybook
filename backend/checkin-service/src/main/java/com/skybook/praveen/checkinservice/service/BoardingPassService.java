package com.skybook.praveen.checkinservice.service;

import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassVerifyResponse;

import java.util.Optional;

public interface BoardingPassService {

    /** Generated once, right after a successful check-in (design doc section 5.3). */
    BoardingPassResponse generate(Long checkInId);

    /**
     * Revokes the current ACTIVE pass (if any) and issues a new one with
     * the updated seat - a boarding pass is a point-in-time document, not a
     * live view (design doc section 5.6/6). Sets reissuedAsId on the
     * revoked row. Empty when the seat changed before any pass was issued
     * (still pre-check-in) - nothing to reissue.
     */
    Optional<BoardingPassResponse> reissueForSeatChange(Long checkInId);

    BoardingPassResponse getById(Long id);

    /**
     * design doc section 6/7 - cryptographic signature check, then confirms
     * the token belongs to a currently ACTIVE pass whose CheckIn is
     * CHECKED_IN (not yet boarded, not revoked/cancelled/no-show). Throws
     * BoardingPassVerificationException on any failure - never returns a
     * "invalid" result, see design doc's 422 contract.
     */
    BoardingPassVerifyResponse verify(String token);
}
