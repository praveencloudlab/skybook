package com.skybook.praveen.checkinservice.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * Signs and verifies the boarding-pass QR token (design doc section 6).
 * token = base64url(payload) + "." + base64url(HMAC-SHA256(payload, key)).
 * Hand-rolled rather than a full JWT library - single purpose, no claims/
 * header machinery needed for one internal token shape.
 *
 * Payload = "boardingPassNumber|bookingReference|flightId|seatNumber|checkInId".
 * Key rotation and per-flight/per-day keys are out of scope for v1
 * (design doc section 14/15) - one static symmetric key from config.
 */
@Component
public class BoardingPassTokenSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** One decoded field per pipe-delimited payload segment. */
    public record TokenPayload(String boardingPassNumber, String bookingReference,
                                Long flightId, String seatNumber, Long checkInId) {
    }

    private final byte[] signingKey;

    public BoardingPassTokenSigner(
            @Value("${checkin.boarding-pass.signing-key:dev-only-insecure-signing-key-change-me}") String signingKey) {
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String boardingPassNumber, String bookingReference,
                        Long flightId, String seatNumber, Long checkInId) {

        String payload = String.join("|",
                boardingPassNumber, bookingReference, String.valueOf(flightId),
                seatNumber == null ? "" : seatNumber, String.valueOf(checkInId));

        String encodedPayload = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(hmac(payload));

        return encodedPayload + "." + signature;
    }

    /** Empty when the token is malformed or the signature doesn't match - never throws on bad input. */
    public Optional<TokenPayload> verify(String token) {

        if (token == null) {
            return Optional.empty();
        }

        int dot = token.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }

        String encodedPayload = token.substring(0, dot);
        String suppliedSignature = token.substring(dot + 1);

        String payload;
        try {
            payload = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }

        String expectedSignature = ENCODER.encodeToString(hmac(payload));
        if (!constantTimeEquals(expectedSignature, suppliedSignature)) {
            return Optional.empty();
        }

        return parsePayload(payload);
    }

    private static Optional<TokenPayload> parsePayload(String payload) {

        String[] parts = payload.split("\\|", -1);
        if (parts.length != 5) {
            return Optional.empty();
        }

        try {
            return Optional.of(new TokenPayload(
                    parts[0], parts[1], Long.valueOf(parts[2]),
                    parts[3].isEmpty() ? null : parts[3], Long.valueOf(parts[4])));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Could not compute boarding pass token signature", e);
        }
    }

    /** Avoids short-circuiting string comparison for signature checks. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
