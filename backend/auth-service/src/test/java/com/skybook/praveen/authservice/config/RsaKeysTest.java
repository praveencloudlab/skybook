package com.skybook.praveen.authservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RS256 key parsing, fail-closed (SECURITY_HARDENING_MODULE.md §5). Auth-service
 * is the only holder of the private key, so a misconfigured or undersized key
 * has to stop the service at boot rather than let it come up signing tokens
 * nobody should trust.
 */
class RsaKeysTest {

    private static final KeyPair STRONG = generate(2048);

    private static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + type + "-----";
    }

    private static String privatePem(KeyPair keyPair) {
        return pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    private static String publicPem(KeyPair keyPair) {
        return pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    @Nested
    @DisplayName("a well-formed 2048-bit keypair parses")
    class HappyPath {

        @Test
        void readsThePrivateKeyBackAtItsFullStrength() {
            var key = RsaKeys.privateKey(privatePem(STRONG));

            assertThat(key.getModulus().bitLength()).isEqualTo(2048);
            assertThat(key.getAlgorithm()).isEqualTo("RSA");
        }

        @Test
        void readsThePublicKeyBackAtItsFullStrength() {
            var key = RsaKeys.publicKey(publicPem(STRONG));

            assertThat(key.getModulus().bitLength()).isEqualTo(2048);
            assertThat(key.getModulus()).isEqualTo(RsaKeys.privateKey(privatePem(STRONG)).getModulus());
        }

        @Test
        void acceptsAKeySuppliedWithoutPemArmourOrLineBreaks() {
            // How the key arrives through a single-line environment variable.
            String bare = Base64.getEncoder().encodeToString(STRONG.getPublic().getEncoded());

            assertThat(RsaKeys.publicKey(bare).getModulus().bitLength()).isEqualTo(2048);
        }
    }

    @Nested
    @DisplayName("a missing key stops the service rather than defaulting")
    class MissingKey {

        @Test
        void refusesANullPrivateKey() {
            assertThatThrownBy(() -> RsaKeys.privateKey(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.private-key is required");
        }

        @Test
        void refusesABlankPrivateKey() {
            assertThatThrownBy(() -> RsaKeys.privateKey("   "))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.private-key is required");
        }

        @Test
        void refusesANullPublicKey() {
            assertThatThrownBy(() -> RsaKeys.publicKey(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.public-key is required");
        }

        @Test
        void refusesABlankPublicKey() {
            assertThatThrownBy(() -> RsaKeys.publicKey(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.public-key is required");
        }
    }

    @Nested
    @DisplayName("a malformed key is named in the failure so the operator can fix it")
    class MalformedKey {

        @Test
        void rejectsAPrivateKeyThatIsNotBase64() {
            assertThatThrownBy(() -> RsaKeys.privateKey("-----BEGIN PRIVATE KEY-----\n!!!\n-----END PRIVATE KEY-----"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.private-key is not valid base64 PEM");
        }

        @Test
        void rejectsAPublicKeyThatIsNotBase64() {
            assertThatThrownBy(() -> RsaKeys.publicKey("not~valid~base64"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.public-key is not valid base64 PEM");
        }

        @Test
        void rejectsWellFormedBase64ThatIsNotAKey() {
            String base64Rubbish = Base64.getEncoder().encodeToString("not a key at all".getBytes());

            assertThatThrownBy(() -> RsaKeys.privateKey(base64Rubbish))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a valid RSA PKCS#8 key");
            assertThatThrownBy(() -> RsaKeys.publicKey(base64Rubbish))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a valid RSA public key");
        }

        @Test
        void rejectsThePublicKeyBeingPastedIntoThePrivateKeySetting() {
            // A realistic copy-paste slip. Only the PRIVATE armour is stripped on
            // this path, so the leftover "-----BEGIN PUBLIC KEY-----" trips the
            // base64 decode before the key spec is ever consulted - the service
            // still refuses to start, and the message names the setting at fault.
            assertThatThrownBy(() -> RsaKeys.privateKey(publicPem(STRONG)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.private-key");
        }

        @Test
        void rejectsThePrivateKeyBeingPastedIntoThePublicKeySetting() {
            assertThatThrownBy(() -> RsaKeys.publicKey(privatePem(STRONG)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.public-key");
        }

        @Test
        void rejectsPublicKeyBytesSuppliedWhereThePrivateKeyBelongs() {
            // The same slip with the armour already gone: well-formed base64, so
            // it gets as far as the key spec and fails there instead.
            String bare = Base64.getEncoder().encodeToString(STRONG.getPublic().getEncoded());

            assertThatThrownBy(() -> RsaKeys.privateKey(bare))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a valid RSA PKCS#8 key");
        }

        @Test
        void rejectsPrivateKeyBytesSuppliedWhereThePublicKeyBelongs() {
            String bare = Base64.getEncoder().encodeToString(STRONG.getPrivate().getEncoded());

            assertThatThrownBy(() -> RsaKeys.publicKey(bare))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a valid RSA public key");
        }
    }

    @Nested
    @DisplayName("an undersized key is refused even though it is otherwise valid")
    class WeakKey {

        private static final KeyPair WEAK = generate(1024);

        @Test
        void refusesAPrivateKeyWeakerThan2048Bits() {
            assertThatThrownBy(() -> RsaKeys.privateKey(privatePem(WEAK)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("weaker than 2048 bits");
        }

        @Test
        void refusesAPublicKeyWeakerThan2048Bits() {
            assertThatThrownBy(() -> RsaKeys.publicKey(publicPem(WEAK)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("weaker than 2048 bits");
        }
    }
}
