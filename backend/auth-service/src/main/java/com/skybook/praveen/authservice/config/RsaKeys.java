package com.skybook.praveen.authservice.config;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses auth-service's RS256 keypair from PEM at startup, fail-closed
 * (SECURITY_HARDENING_MODULE.md §5). The private key is unique to auth-service;
 * the public counterpart is what every other service verifies with.
 */
public final class RsaKeys {

    private static final int MIN_RSA_BITS = 2048;

    private RsaKeys() {
    }

    public static RSAPrivateKey privateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("jwt.private-key is required but was not set");
        }
        byte[] der = decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""), "jwt.private-key");
        try {
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof RSAPrivateKey rsa)) {
                throw new IllegalStateException("jwt.private-key must be an RSA key");
            }
            if (rsa.getModulus().bitLength() < MIN_RSA_BITS) {
                throw new IllegalStateException("jwt.private-key is weaker than " + MIN_RSA_BITS + " bits");
            }
            return rsa;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("jwt.private-key is not a valid RSA PKCS#8 key", e);
        }
    }

    public static RSAPublicKey publicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("jwt.public-key is required but was not set");
        }
        byte[] der = decode(pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", ""), "jwt.public-key");
        try {
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof RSAPublicKey rsa)) {
                throw new IllegalStateException("jwt.public-key must be an RSA key");
            }
            if (rsa.getModulus().bitLength() < MIN_RSA_BITS) {
                throw new IllegalStateException("jwt.public-key is weaker than " + MIN_RSA_BITS + " bits");
            }
            return rsa;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("jwt.public-key is not a valid RSA public key", e);
        }
    }

    private static byte[] decode(String base64Body, String name) {
        try {
            return Base64.getDecoder().decode(base64Body.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not valid base64 PEM", e);
        }
    }
}
