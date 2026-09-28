package com.centralArchiveSystem.Archive.import_.migration.setting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for the small set of settings that must not sit in the database in clear
 * text. The key is derived by hashing the configured secret, so any secret length is accepted and
 * no key-length validation is needed at startup.
 *
 * <p>Ciphertext is stored as {@code v1:<base64 iv>:<base64 ciphertext+tag>}. The version prefix
 * leaves room to rotate to a different scheme later without guessing how old rows were written.
 */
@Component
public class SettingsCrypto {

    private static final String PREFIX = "v1";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SettingsCrypto(@Value("${security.settings-encryption-key}") String secret) {
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    /** Returns null for null/blank input so callers can treat "no value" as a single case. */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(sealed);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt migration setting", e);
        }
    }

    /** Returns null for null/blank input. Throws when a stored value was written with another key. */
    public String decrypt(String envelope) {
        if (envelope == null || envelope.isBlank()) {
            return null;
        }
        String[] parts = envelope.split(":", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new IllegalStateException("Unrecognised migration setting ciphertext format");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] sealed = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to decrypt migration setting. Was SETTINGS_ENCRYPTION_KEY changed after it was saved?", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
