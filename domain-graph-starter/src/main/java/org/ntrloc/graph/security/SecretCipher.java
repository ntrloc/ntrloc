package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.SecurityProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

// Encrypts secrets kept in the database (LDAP bind password, OIDC client secrets) with graph.security.secret-key,
// which lives outside the database, so a database dump alone doesn't reveal them. AES-GCM, so a
// tampered value fails to decrypt rather than decrypting to garbage. The "v1:" prefix leaves room
// to change the scheme later without guessing how an old value was written.
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecurityProperties properties) {
        String encoded = properties.getSecretKey();
        if (encoded == null || encoded.isBlank()) {
            this.key = null;
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("graph.security.secret-key isn't valid base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("graph.security.secret-key must be 32 bytes, base64-encoded "
                    + "(e.g. the output of `openssl rand -base64 32`), not " + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Couldn't encrypt a secret", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Not a value this cipher wrote");
        }
        byte[] bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Couldn't decrypt a stored secret: graph.security.secret-key "
                    + "isn't the key it was encrypted with, or the value was altered", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("graph.security.secret-key isn't set");
        }
    }
}
