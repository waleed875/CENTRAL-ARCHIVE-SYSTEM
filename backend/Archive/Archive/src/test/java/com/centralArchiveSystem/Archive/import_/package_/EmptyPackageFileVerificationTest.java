package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class EmptyPackageFileVerificationTest {
    private final PackageArchiveService service = new PackageArchiveService(new ObjectMapper(), "1.0");

    @Test
    void acceptsSignedManifestEmptyTextEntryOnly() throws Exception {
        var result = verify("package.txt", "txt", emptyChecksum());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(result.filesValid());
        assertEquals(1, result.filesCount());
        assertEquals(0L, result.verifiedFileBytes());
    }

    @Test
    void rejectsEmptyBinaryAndWrongChecksum() throws Exception {
        assertFalse(verify("document.pdf", "pdf", emptyChecksum()).filesValid());
        assertFalse(verify("package.txt", "txt", "a".repeat(64)).filesValid());
    }

    private PackageArchiveService.Verification verify(String fileName, String extension,
                                                       String checksum) throws Exception {
        String manifest = """
            {
              "package_uuid":"11111111-1111-1111-1111-111111111111",
              "schema_version":"1.0",
              "source_terminal_uuid":"22222222-2222-2222-2222-222222222222",
              "projects":[{
                "project_id":"33333333-3333-3333-3333-333333333333",
                "project_number":"T-1",
                "name":"Empty archive fixture",
                "year":2026,
                "files":[{
                  "project_id":"33333333-3333-3333-3333-333333333333",
                  "object_key":"files/%s",
                  "original_name":"%s",
                  "extension":"%s",
                  "size_bytes":0,
                  "checksum_sha256":"%s"
                }]
              }]
            }
            """.formatted(fileName, fileName, extension, checksum);
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifestBytes);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("files/" + fileName));
            zip.closeEntry();
        }
        return service.verifyContents(new ByteArrayInputStream(output.toByteArray()),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)),
                "1.0");
    }

    private static String emptyChecksum() {
        return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    }
}
