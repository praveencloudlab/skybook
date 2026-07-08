package com.skybook.praveen.checkinservice.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BoardingPassTokenSignerTest {

    private final BoardingPassTokenSigner signer = new BoardingPassTokenSigner("test-signing-key");

    @Test
    void signThenVerifyRoundTripReturnsTheOriginalFields() {

        String token = signer.sign("BP-2026-K7M4Z9", "SB8U33", 1L, "12B", 42L);

        Optional<BoardingPassTokenSigner.TokenPayload> result = signer.verify(token);

        assertThat(result).isPresent();
        assertThat(result.get().boardingPassNumber()).isEqualTo("BP-2026-K7M4Z9");
        assertThat(result.get().bookingReference()).isEqualTo("SB8U33");
        assertThat(result.get().flightId()).isEqualTo(1L);
        assertThat(result.get().seatNumber()).isEqualTo("12B");
        assertThat(result.get().checkInId()).isEqualTo(42L);
    }

    @Test
    void nullSeatNumberRoundTripsAsNull() {

        String token = signer.sign("BP-2026-K7M4Z9", "SB8U33", 1L, null, 42L);

        Optional<BoardingPassTokenSigner.TokenPayload> result = signer.verify(token);

        assertThat(result).isPresent();
        assertThat(result.get().seatNumber()).isNull();
    }

    @Test
    void rejectsATamperedSignature() {

        String token = signer.sign("BP-2026-K7M4Z9", "SB8U33", 1L, "12B", 42L);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + flipLastChar(parts[1]);

        assertThat(signer.verify(tampered)).isEmpty();
    }

    @Test
    void rejectsATamperedPayload() {

        String token = signer.sign("BP-2026-K7M4Z9", "SB8U33", 1L, "12B", 42L);
        String[] parts = token.split("\\.");
        String tampered = flipLastChar(parts[0]) + "." + parts[1];

        assertThat(signer.verify(tampered)).isEmpty();
    }

    @Test
    void rejectsAMalformedToken() {
        assertThat(signer.verify("not-a-real-token")).isEmpty();
        assertThat(signer.verify("")).isEmpty();
        assertThat(signer.verify(null)).isEmpty();
    }

    @Test
    void rejectsATokenSignedWithADifferentKey() {

        BoardingPassTokenSigner otherSigner = new BoardingPassTokenSigner("a-completely-different-key");
        String token = otherSigner.sign("BP-2026-K7M4Z9", "SB8U33", 1L, "12B", 42L);

        assertThat(signer.verify(token)).isEmpty();
    }

    private static String flipLastChar(String s) {
        char last = s.charAt(s.length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        return s.substring(0, s.length() - 1) + flipped;
    }
}
