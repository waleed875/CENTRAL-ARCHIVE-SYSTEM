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
    void executableLegacyContentIsPreservedButQuarantined() throws Exception {
        byte[] executable = new byte[]{0x4D, 0x5A, 0x00, 0x01, 0x02};
        var prepared = LegacyMigrationService.prepareLegacyFile(
                new ByteArrayInputStream(Base64.getEncoder().encode(executable)),
                "malware.exe", ".exe");
        try {
            assertTrue(prepared.quarantined());
            assertEquals("application/octet-stream", prepared.mimeType());
            assertArrayEquals(executable, Files.readAllBytes(prepared.path()));
            assertEquals(sha256(executable), prepared.checksumSha256());
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    @Test
    void mismatchedLegacySuffixPreservesOriginalInQuarantine() throws Exception {
        byte[] content = "%PDF-1.7\nlegacy archive\n".getBytes(StandardCharsets.US_ASCII);
        var prepared = LegacyMigrationService.prepareLegacyFile(
                new ByteArrayInputStream(Base64.getEncoder().encode(content)),
                "document.pdf", ".txt");
        try {
            assertTrue(prepared.quarantined());
            assertTrue(prepared.quarantineReason().contains("do not match"));
            assertEquals("application/octet-stream", prepared.mimeType());
            assertArrayEquals(content, Files.readAllBytes(prepared.path()));
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    @Test
    void preservesEngineeringArchiveBytesAndMetadataForMinio() throws Exception {
        byte[] opaque = new byte[]{0, 1, 4, 5, 0, 7, (byte)0xff};
        for (String extension : new String[]{"dbk", "dsn", "opj", "mdl", "dat"}) {
            var prepared = LegacyMigrationService.prepareLegacyFile(
                    new ByteArrayInputStream(Base64.getEncoder().encode(opaque)),
                    "source." + extension, "." + extension);
            try {
                assertEquals("application/octet-stream", prepared.mimeType());
                assertEquals(extension, prepared.extension());
                assertArrayEquals(opaque, Files.readAllBytes(prepared.path()));
                assertEquals(sha256(opaque), prepared.checksumSha256());
            } finally {
                Files.deleteIfExists(prepared.path());
            }
        }
    }

    @Test
    void disguisedExecutableAndUnidentifiedExtensionAreQuarantined() throws Exception {
        byte[] executable = new byte[]{'M', 'Z', 0, 2, 0, 1};
        for (String ext : new String[]{"dat", "unknown", "exe"}) {
            var prepared = LegacyMigrationService.prepareLegacyFile(
                    new ByteArrayInputStream(Base64.getEncoder().encode(executable)),
                    "file." + ext, ext);
            try {
                assertTrue(prepared.quarantined());
                assertArrayEquals(executable, Files.readAllBytes(prepared.path()));
            } finally {
                Files.deleteIfExists(prepared.path());
            }
        }
    }

    @Test
    void preservesUnknownAndExtensionlessFilesAsOpaqueQuarantine() throws Exception {
        byte[] bytes = {0, 3, 5, (byte) 255};
        var prepared = LegacyMigrationService.prepareLegacyFile(
                new ByteArrayInputStream(Base64.getEncoder().encode(bytes)),
                "unidentified", null);
        try {
            assertEquals("bin", prepared.extension());
            assertTrue(prepared.quarantined());
            assertArrayEquals(bytes, Files.readAllBytes(prepared.path()));
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    @Test
    void countersRemainAccurateUnderConcurrentUpdates() {
        LegacyMigrationService.LegacyFileSummary summary =
                new LegacyMigrationService.LegacyFileSummary();

        java.util.stream.IntStream.range(0, 10_000)
                .parallel()
                .forEach(index -> {
                    summary.incrementDone();
                    summary.incrementFailed();
                    summary.incrementSkipped();
                });

        assertEquals(10_000, summary.done());
        assertEquals(10_000, summary.failed());
        assertEquals(10_000, summary.skipped());
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
