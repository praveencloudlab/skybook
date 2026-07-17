package com.skybook.praveen.security;

import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaPublicKeysTest {

    @Test
    void parsesAValid2048BitPemWithArmor() {
        TestTokens tokens = TestTokens.rsa2048();
        RSAPublicKey key = RsaPublicKeys.parse(tokens.publicKeyPem());
        assertThat(key.getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
    }

    @Test
    void rejectsAMissingKey() {
        assertThatThrownBy(() -> RsaPublicKeys.parse(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> RsaPublicKeys.parse("not-a-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAKeyWeakerThan2048Bits() {
        var weak = TestTokens.generate(1024);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(weak.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        assertThatThrownBy(() -> RsaPublicKeys.parse(pem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }
}
