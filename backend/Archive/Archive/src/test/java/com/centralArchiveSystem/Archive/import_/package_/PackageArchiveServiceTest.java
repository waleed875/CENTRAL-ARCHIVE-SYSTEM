package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackageArchiveServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PackageArchiveService service = new PackageArchiveService(objectMapper, "1.0");

    @Test
    void inspectsAndVerifiesValidPackage() throws Exception {
        byte[] fileContent = "central archive test file".getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "1.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "project_number": "P-001",
                    "name": "Test Project",
                    "year": 2026,
                    "files": [{
                      "project_id": "33333333-3333-3333-3333-333333333333",
                      "original_name": "document.txt",
                      "object_key": "files/document.txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, fileHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        MockMultipartFile multipart = new MockMultipartFile(
                "package_file",
                "sample.pkg",
                "application/octet-stream",
                packageBytes
        );

        PackageArchiveService.Inspection inspection = service.inspect(multipart);

        assertNotEquals(inspection.packageHash(), inspection.manifestHash());
        assertEquals("1.0", inspection.manifest().path("schema_version").asText());

        PackageArchiveService.Verification verification = service.verifyContents(
                new ByteArrayInputStream(packageBytes),
                inspection.manifestHash(),
                "1.0"
        );

        assertTrue(verification.manifestHashValid());
        assertTrue(verification.schemaVersionValid());
        assertTrue(verification.filesValid());
        assertTrue(verification.errors().isEmpty());
        assertEquals(1, verification.projectsCount());
        assertEquals(1, verification.filesCount());
        assertEquals(fileContent.length, verification.verifiedFileBytes());
    }

    @Test
    void rejectsFileChecksumMismatch() throws Exception {
        byte[] fileContent = "actual content".getBytes(StandardCharsets.UTF_8);
        String wrongHash = sha256("different content".getBytes(StandardCharsets.UTF_8));

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "1.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "project_number": "P-001",
                    "name": "Test Project",
                    "year": 2026,
                    "files": [{
                      "project_id": "33333333-3333-3333-3333-333333333333",
                      "original_name": "document.txt",
                      "object_key": "files/document.txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, wrongHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        String manifestHash = sha256(manifest.getBytes(StandardCharsets.UTF_8));

        PackageArchiveService.Verification verification = service.verifyContents(
                new ByteArrayInputStream(packageBytes),
                manifestHash,
                "1.0"
        );

        assertFalse(verification.filesValid());
        assertTrue(verification.errors().stream()
                .anyMatch(error -> error.contains("Checksum mismatch")));
    }


    @Test
    void rejectsExecutableDisguisedAsPdfInsidePackage() throws Exception {
        byte[] fileContent = new byte[]{0x4D, 0x5A, 0x00, 0x00, 0x01};
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "1.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "project_number": "P-001",
                    "name": "Test Project",
                    "year": 2026,
                    "files": [{
                      "project_id": "33333333-3333-3333-3333-333333333333",
                      "original_name": "report.pdf",
                      "extension": "pdf",
                      "object_key": "files/document.txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, fileHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        String manifestHash = sha256(manifest.getBytes(StandardCharsets.UTF_8));

        PackageArchiveService.Verification verification = service.verifyContents(
                new ByteArrayInputStream(packageBytes),
                manifestHash,
                "1.0"
        );

        assertFalse(verification.filesValid());
        assertTrue(verification.errors().stream()
                .anyMatch(error -> error.contains("Content type mismatch")));
    }

    @Test
    void rejectsOrdinaryZipDisguisedAsDocxInsidePackage() throws Exception {
        ByteArrayOutputStream innerOutput = new ByteArrayOutputStream();
        try (ZipOutputStream innerZip = new ZipOutputStream(innerOutput)) {
            innerZip.putNextEntry(new ZipEntry("random.txt"));
            innerZip.write("not an office document".getBytes(StandardCharsets.UTF_8));
            innerZip.closeEntry();
        }
        byte[] fileContent = innerOutput.toByteArray();
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "1.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "project_number": "P-001",
                    "name": "Test Project",
                    "year": 2026,
                    "files": [{
                      "project_id": "33333333-3333-3333-3333-333333333333",
                      "original_name": "document.docx",
                      "extension": "docx",
                      "object_key": "files/document.txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, fileHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        String manifestHash = sha256(manifest.getBytes(StandardCharsets.UTF_8));

        PackageArchiveService.Verification verification = service.verifyContents(
                new ByteArrayInputStream(packageBytes),
                manifestHash,
                "1.0"
        );

        assertFalse(verification.filesValid());
        assertTrue(verification.errors().stream()
                .anyMatch(error -> error.contains("Office document type")));
    }


    @Test
    void rejectsPackageEntryThatExceedsExpandedFileLimit() throws Exception {
        byte[] fileContent = "content larger than test expansion limit"
                .getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "1.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "project_number": "P-001",
                    "name": "Test Project",
                    "year": 2026,
                    "files": [{
                      "project_id": "33333333-3333-3333-3333-333333333333",
                      "original_name": "document.txt",
                      "extension": "txt",
                      "object_key": "files/document.txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, fileHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        String manifestHash = sha256(manifest.getBytes(StandardCharsets.UTF_8));

        setField(service, "maxExpandedFileBytes", 8L);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.verifyContents(
                        new ByteArrayInputStream(packageBytes),
                        manifestHash,
                        "1.0"
                )
        );
    }


    @Test
    void rejectsManifestExtensionThatDisagreesWithOriginalFilename() throws Exception {
        byte[] fileContent = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "1.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "project_number": "P-001",
                    "name": "Test Project",
                    "year": 2026,
                    "files": [{
                      "project_id": "33333333-3333-3333-3333-333333333333",
                      "original_name": "report.pdf",
                      "extension": "txt",
                      "object_key": "files/document.txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, fileHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        String manifestHash = sha256(manifest.getBytes(StandardCharsets.UTF_8));

        PackageArchiveService.Verification verification = service.verifyContents(
                new ByteArrayInputStream(packageBytes),
                manifestHash,
                "1.0"
        );

        assertFalse(verification.filesValid());
        assertTrue(verification.errors().stream()
                .anyMatch(error -> error.contains("Manifest extension does not match")));
    }

    @Test
    void rejectsManifestWithMissingRequiredProjectFields() throws Exception {
        byte[] fileContent = "content".getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "1.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "name": "Missing Number Project",
                    "year": 2026,
                    "files": [{
                      "object_key": "files/document.txt",
                      "original_name": "document.txt",
                      "extension": "txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, fileHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        String manifestHash = sha256(manifest.getBytes(StandardCharsets.UTF_8));

        PackageArchiveService.Verification verification = service.verifyContents(
                new ByteArrayInputStream(packageBytes),
                manifestHash,
                "1.0"
        );

        assertFalse(verification.filesValid());
        assertTrue(verification.errors().stream()
                .anyMatch(error -> error.contains("project_number")));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws Exception {
        byte[] fileContent = "content".getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid": "11111111-1111-1111-1111-111111111111",
                  "package_name": "sample.pkg",
                  "schema_version": "2.0",
                  "source_terminal_uuid": "22222222-2222-2222-2222-222222222222",
                  "projects": [{
                    "project_id": "33333333-3333-3333-3333-333333333333",
                    "project_number": "P-001",
                    "name": "Test Project",
                    "year": 2026,
                    "files": [{
                      "object_key": "files/document.txt",
                      "size_bytes": %d,
                      "checksum_sha256": "%s"
                    }]
                  }]
                }
                """.formatted(fileContent.length, fileHash);

        byte[] packageBytes = createPackage(manifest, fileContent);
        String manifestHash = sha256(manifest.getBytes(StandardCharsets.UTF_8));

        PackageArchiveService.Verification verification = service.verifyContents(
                new ByteArrayInputStream(packageBytes),
                manifestHash,
                "2.0"
        );

        assertFalse(verification.schemaVersionValid());
        assertTrue(verification.errors().stream()
                .anyMatch(error -> error.contains("schema_version")));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private byte[] createPackage(String manifest, byte[] fileContent) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("files/document.txt"));
            zip.write(fileContent);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)
        );
    }
}
