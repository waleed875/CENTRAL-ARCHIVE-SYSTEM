package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMigrationServiceFilePreparationTest {

    @Test
    void streamsBase64ToSpoolAndCalculatesVerifiedMetadata() throws Exception {
        byte[] content = "%PDF-1.7\nlegacy archive\n".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = Base64.getEncoder().encode(content);

        LegacyMigrationService.PreparedLegacyFile prepared =
                LegacyMigrationService.prepareLegacyFile(
                        new ByteArrayInputStream(encoded),
                        "document.pdf",
                        ".PDF"
                );

        try {
            assertEquals("pdf", prepared.extension());
            assertEquals("application/pdf", prepared.mimeType());
            assertEquals(content.length, prepared.sizeBytes());
            assertEquals(sha256(content), prepared.checksumSha256());
            assertArrayEquals(content, Files.readAllBytes(prepared.path()));
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    @Test
    void resolvesExtensionFromOriginalNameWhenLegacySuffixIsMissing() throws Exception {
        byte[] content = "plain legacy text".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = Base64.getEncoder().encode(content);

        LegacyMigrationService.PreparedLegacyFile prepared =
                LegacyMigrationService.prepareLegacyFile(
                        new ByteArrayInputStream(encoded),
                        "notes.txt",
                        null
                );

        try {
            assertEquals("txt", prepared.extension());
            assertEquals("text/plain", prepared.mimeType());
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    @Test
    void rejectsExecutableLegacyContentAndRemovesSpool() {
        byte[] executable = new byte[]{0x4D, 0x5A, 0x00, 0x01, 0x02};
        byte[] encoded = Base64.getEncoder().encode(executable);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> LegacyMigrationService.prepareLegacyFile(
                        new ByteArrayInputStream(encoded),
                        "malware.exe",
                        ".exe"
                )
        );

        assertTrue(error.getMessage().contains("Executable"));
    }

    private String sha256(byte[] content) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
