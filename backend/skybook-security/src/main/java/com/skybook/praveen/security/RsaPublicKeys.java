package com.skybook.praveen.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses an RS256 verification key from PEM (SECURITY_HARDENING_MODULE.md §5).
 * Verify-only helper - there is no private-key counterpart in this module by
 * design; only auth-service signs.
 */
public final class RsaPublicKeys {

    private static final int MIN_RSA_BITS = 2048;

    private RsaPublicKeys() {
    }

    /**
     * @param pem base64 SubjectPublicKeyInfo, with or without the PEM armor and
     *            surrounding whitespace/newlines.
     * @throws IllegalStateException if the key is missing, malformed, not RSA,
     *                               or weaker than 2048 bits (fail closed at boot).
     */
    public static RSAPublicKey parse(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("skybook.security.public-key is required but was not set");
        }

        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("skybook.security.public-key is not valid base64 PEM", e);
        }

        PublicKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("skybook.security.public-key is not a valid RSA public key", e);
        }

        if (!(key instanceof RSAPublicKey rsaKey)) {
            throw new IllegalStateException("skybook.security.public-key must be an RSA key");
        }
        int bits = rsaKey.getModulus().bitLength();
        if (bits < MIN_RSA_BITS) {
            throw new IllegalStateException(
                    "skybook.security.public-key is " + bits + "-bit; RS256 requires at least " + MIN_RSA_BITS);
        }
        return rsaKey;
    }
}
