package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SealedPackageDecryptor {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int BUFFER_SIZE = 8192;

    @Value("${archive.packages.max-decrypted-payload-bytes:42949672960}")
    private long maxDecryptedPayloadBytes = 40L * 1024 * 1024 * 1024;

    public DecryptedPayload decrypt(MultipartFile packageFile,
                                    SealedPackageInspector.Inspection inspection,
                                    String passphrase) throws Exception {
        if (packageFile == null || packageFile.isEmpty()) {
            throw new IllegalArgumentException("Package file is required");
        }
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalArgumentException(
                    "passphrase is required for encrypted .zip/.nxpkg packages");
        }
        if (inspection == null || inspection.header() == null) {
            throw new IllegalArgumentException("Encrypted package inspection is required");
        }

        Path output = Files.createTempFile("central-archive-decrypted-", ".zip");
        boolean success = false;
        try {
            DecryptionStats stats;
            try (InputStream packageInput = packageFile.getInputStream()) {
                stats = decryptOuterArchive(
                        packageInput,
                        inspection.header(),
                        inspection.payloadSizeBytes(),
                        passphrase,
                        output
                );
            }
            success = true;
            return new DecryptedPayload(output, stats.plaintextBytes(), stats.sha256());
        } finally {
            if (!success) {
                Files.deleteIfExists(output);
            }
        }
    }

    private DecryptionStats decryptOuterArchive(InputStream packageInput,
                                                JsonNode header,
                                                long sealedPayloadBytes,
                                                String passphrase,
                                                Path output) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(packageInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && "payload.enc".equals(normalizeRoot(entry.getName()))) {
                    return decryptFramedPayload(
                            zip,
                            header,
                            sealedPayloadBytes,
                            passphrase,
                            output
                    );
                }
                drain(zip);
                zip.closeEntry();
            }
        }
        throw new IllegalArgumentException("payload.enc was not found in the encrypted package");
    }

    private DecryptionStats decryptFramedPayload(InputStream encryptedInput,
                                                 JsonNode header,
                                                 long totalSealedBytes,
                                                 String passphrase,
                                                 Path output) throws Exception {
        if (totalSealedBytes <= 0) {
            throw new IllegalArgumentException("Encrypted payload is empty");
        }

        JsonNode encryption = header.path("encryption");
        int iterations = requiredPositiveInt(encryption, "iterations");
        int chunkSize = requiredPositiveInt(encryption, "chunkSize");
        byte[] salt = decodeBase64(requiredText(encryption, "salt"), "encryption.salt");
        byte[] keyBytes = deriveKey(passphrase, salt, iterations);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long sealedPosition = 0L;
        long plaintextBytes = 0L;
        int frameIndex = 0;

        try (DataInputStream input = new DataInputStream(encryptedInput);
             OutputStream plainOutput = Files.newOutputStream(output)) {

            while (sealedPosition < totalSealedBytes) {
                if (totalSealedBytes - sealedPosition < 4L + NONCE_BYTES + TAG_BYTES) {
                    throw new IllegalArgumentException("Encrypted payload is truncated");
                }

                int plainLength;
                try {
                    plainLength = input.readInt();
                } catch (java.io.EOFException e) {
                    throw new IllegalArgumentException("Encrypted payload is truncated", e);
                }

                if (plainLength < 0 || plainLength > chunkSize) {
                    throw new IllegalArgumentException(
                            "Encrypted frame length exceeds declared chunkSize");
                }

                long frameLength = 4L + NONCE_BYTES + (long) plainLength + TAG_BYTES;
                if (sealedPosition + frameLength > totalSealedBytes) {
                    throw new IllegalArgumentException("Encrypted payload is truncated");
                }

                byte[] nonce = input.readNBytes(NONCE_BYTES);
                byte[] ciphertextAndTag = input.readNBytes(plainLength + TAG_BYTES);
                if (nonce.length != NONCE_BYTES
                        || ciphertextAndTag.length != plainLength + TAG_BYTES) {
                    throw new IllegalArgumentException("Encrypted payload is truncated");
                }

                sealedPosition += frameLength;
                boolean lastFrame = sealedPosition == totalSealedBytes;
                byte[] aad = ByteBuffer.allocate(5)
                        .putInt(frameIndex)
                        .put((byte) (lastFrame ? 1 : 0))
                        .array();

                byte[] plaintext;
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(
                            Cipher.DECRYPT_MODE,
                            new SecretKeySpec(keyBytes, "AES"),
                            new GCMParameterSpec(TAG_BYTES * 8, nonce)
                    );
                    cipher.updateAAD(aad);
                    plaintext = cipher.doFinal(ciphertextAndTag);
                } catch (AEADBadTagException e) {
                    throw new IllegalArgumentException("Package passphrase is incorrect", e);
                }

                if (plaintext.length != plainLength) {
                    throw new IllegalArgumentException("Encrypted frame plaintext length mismatch");
                }

                plaintextBytes += plaintext.length;
                if (plaintextBytes > maxDecryptedPayloadBytes) {
                    throw new IllegalArgumentException(
                            "Decrypted payload exceeds maximum allowed size");
                }

                plainOutput.write(plaintext);
                digest.update(plaintext);
                Arrays.fill(plaintext, (byte) 0);
                frameIndex++;
            }

            if (sealedPosition != totalSealedBytes || frameIndex == 0) {
                throw new IllegalArgumentException("Encrypted payload is incomplete");
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }

        return new DecryptionStats(plaintextBytes, toHex(digest.digest()));
    }

    private byte[] deriveKey(String passphrase, byte[] salt, int iterations) throws Exception {
        char[] chars = passphrase.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    private String normalizeRoot(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Encrypted package entry path is missing");
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("Unsafe encrypted package entry path: " + name);
        }
        return normalized;
    }

    private void drain(InputStream input) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (input.read(buffer) != -1) {
            // consume current ZIP entry
        }
    }

    private String requiredText(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private int requiredPositiveInt(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.path(field).canConvertToInt()) {
            throw new IllegalArgumentException("Missing or invalid integer field: " + field);
        }
        int value = node.path(field).asInt();
        if (value <= 0) {
            throw new IllegalArgumentException("Field must be positive: " + field);
        }
        return value;
    }

    private byte[] decodeBase64(String value, String field) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 in " + field);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record DecryptionStats(long plaintextBytes, String sha256) {}

    public static final class DecryptedPayload implements AutoCloseable {
        private final Path path;
        private final long sizeBytes;
        private final String sha256;

        private DecryptedPayload(Path path, long sizeBytes, String sha256) {
            this.path = path;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        public Path path() {
            return path;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        public String sha256() {
            return sha256;
        }

        @Override
        public void close() throws Exception {
            Files.deleteIfExists(path);
        }
    }
}
