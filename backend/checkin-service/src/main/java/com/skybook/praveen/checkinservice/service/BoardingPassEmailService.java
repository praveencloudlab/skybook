package com.skybook.praveen.checkinservice.service;

import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.entity.BoardingPassEmailLog;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.exception.BoardingPassEmailException;
import com.skybook.praveen.checkinservice.producer.CheckInEventProducer;
import com.skybook.praveen.checkinservice.repository.BoardingPassEmailLogRepository;
import com.skybook.praveen.security.SecurityAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Boarding-pass email re-send (GUEST_CHECKIN_MODULE.md §5): the passenger (or
 * owner, or admin) chooses an address and the EXISTING notification pipeline -
 * QR, HTML, PDF attachment - delivers to it. This service adds only what the
 * original check-in-time emission lacks: a state gate, a shared-state
 * throttle, and an audit row per delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardingPassEmailService {

    private static final int MAX_SENDS_PER_WINDOW = 3;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final CheckInService checkInService;
    private final BoardingPassService boardingPassService;
    private final BoardingPassEmailLogRepository emailLogRepository;
    private final CheckInEventProducer eventProducer;

    @Transactional
    public void emailBoardingPass(Long checkInId, String rawEmail) {
        CheckInResponse checkIn = checkInService.getById(checkInId);

        // No pass, no email: before check-in there is nothing to send, and an
        // endpoint that mails on demand without this gate is a spam cannon.
        if (checkIn.status() != CheckInStatus.CHECKED_IN && checkIn.status() != CheckInStatus.BOARDED) {
            throw BoardingPassEmailException.notCheckedIn();
        }

        Instant windowStart = Instant.now().minus(WINDOW);
        if (emailLogRepository.countByCheckInIdAndSentAtAfter(checkInId, windowStart) >= MAX_SENDS_PER_WINDOW) {
            throw BoardingPassEmailException.throttled();
        }

        BoardingPassResponse pass = boardingPassService.getActiveForCheckIn(checkInId);
        String email = rawEmail.trim();
        String requestedBy = String.valueOf(SecurityAccess.currentSubject());
        String resendId = UUID.randomUUID().toString();

        BoardingPassEmailLog logRow = new BoardingPassEmailLog();
        logRow.setCheckInId(checkInId);
        logRow.setResendId(resendId);
        logRow.setRequestedBy(requestedBy);
        logRow.setAddressHash(sha256Hex(email.toLowerCase(Locale.ROOT)));
        emailLogRepository.save(logRow);

        eventProducer.publishBoardingPassEmailRequested(checkIn, pass, email, resendId, requestedBy);
        log.info("Boarding-pass email requested for check-in {} by {} (resendId {})",
                checkInId, requestedBy, resendId);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
