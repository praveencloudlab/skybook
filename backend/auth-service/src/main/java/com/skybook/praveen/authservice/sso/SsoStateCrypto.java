package com.skybook.praveen.authservice.sso;

import com.skybook.praveen.authservice.config.JwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Seals and unseals the pending-auth payload for the SSO cookie
 * (SSO_MODULE.md §3.3).
 *
 * <p><b>Why encrypt at all:</b> the payload carries the OAuth state, OIDC
 * nonce and PKCE verifier. In the default Spring arrangement those live in the
 * server session; here they ride through the browser, so AES-GCM provides what
 * the session's server-sidedness provided - confidentiality and integrity. A
 * tampered or forged cookie fails authentication of the ciphertext and is
 * treated as absent.
 *
 * <p><b>Why this key:</b> derived (SHA-256) from the RS256 private key PEM the
 * service already holds - introducing a second secret for a five-minute cookie
 * would double the key-management surface for no gain. Rotating the RS256 key
 * invalidates in-flight sign-ins within a five-minute window: acceptable, and
 * exactly the kind of trade the design records rather than hides.
 *
 * <p><b>Why deserialization is safe here:</b> Java deserialization of
 * browser-supplied bytes is normally a gadget-chain hazard - but these bytes
 * only reach the ObjectInputStream after GCM authentication proves the server
 * sealed them. Defence in depth anyway: an {@link ObjectInputFilter} allowlists
 * the exact packages the payload can contain, with size and depth caps, so
 * even a hypothetical future bug that bypasses the seal cannot instantiate
 * arbitrary classes.
 */
@Component
public class SsoStateCrypto {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final ObjectInputFilter DESERIALIZATION_ALLOWLIST = ObjectInputFilter.Config.createFilter(
            // The payload is one small record of strings and a boolean - the
            // allowlist matches exactly that and nothing more. (It used to
            // admit org.springframework.security.oauth2.core.** for the
            // serialized authorization request; the request object is no
            // longer round-tripped - see SsoPendingAuth - so the surface
            // shrinks with it.)
            "maxbytes=4096;maxdepth=8;"
                    + "com.skybook.praveen.authservice.sso.*;"
                    + "java.lang.*;!*");

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public SsoStateCrypto(JwtProperties jwtProperties) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(jwtProperties.getPrivateKey().getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(digest, "AES");
    }

    /** Serialize + encrypt + URL-safe encode. IV is prepended to the ciphertext. */
    public String seal(SsoPendingAuth payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(payload);
            }

            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(bytes.toByteArray());

            byte[] wire = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, wire, 0, iv.length);
            System.arraycopy(ciphertext, 0, wire, iv.length, ciphertext.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(wire);
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Could not seal the SSO pending-auth payload", e);
        }
    }

    /**
     * Decode + decrypt + deserialize. Empty on ANY failure - a cookie that does
     * not unseal cleanly is indistinguishable from no cookie, which downstream
     * turns into the generic {@code sso_failed} outcome rather than an error
     * page that would tell a tamperer what broke.
     */
    public Optional<SsoPendingAuth> unseal(String cookieValue) {
        try {
            byte[] wire = Base64.getUrlDecoder().decode(cookieValue);
            if (wire.length <= GCM_IV_BYTES) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOfRange(wire, 0, GCM_IV_BYTES)));
            byte[] plain = cipher.doFinal(Arrays.copyOfRange(wire, GCM_IV_BYTES, wire.length));

            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(plain))) {
                in.setObjectInputFilter(DESERIALIZATION_ALLOWLIST);
                return Optional.of((SsoPendingAuth) in.readObject());
            }
        } catch (RuntimeException | GeneralSecurityException | java.io.IOException | ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
