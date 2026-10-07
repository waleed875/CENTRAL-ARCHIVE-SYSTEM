package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SealedPayloadCanonicalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SealedPayloadCanonicalizer canonicalizer =
            new SealedPayloadCanonicalizer(mapper);

    @Test
    void resolvesSignedHeaderFileAgainstInnerDocumentsManifest() throws Exception {
        byte[] fileBytes = "canonical payload content".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(fileBytes));

        UUID packageUuid = UUID.randomUUID();
        UUID terminalUuid = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        ObjectNode header = mapper.createObjectNode();
        header.put("package_uuid", packageUuid.toString());
        header.put("package_name", "sample.zip");
        header.put("schema_version", "1.0");
        header.put("source_terminal_uuid", terminalUuid.toString());
        header.put("source_organization_id", organizationId.toString());
        header.put("source_username", "branch@example.test");

        ObjectNode project = header.putArray("projects").addObject();
        project.put("project_id", projectId.toString());
        project.put("project_number", "1");
        project.put("name", "Sample");
        project.put("year", 2026);
        project.put("organization_entity_id", organizationId.toString());
        project.putArray("members");

        ObjectNode file = project.putArray("files").addObject();
        file.put("project_id", projectId.toString());
        file.put("original_name", "test.txt");
        file.put("name", "test.txt");
        file.put("extension", "txt");
        file.put("size_bytes", fileBytes.length);
        file.put("checksum_sha256", checksum);
        file.put("object_key", "projects/1/test.txt");
        file.put("content_base64", "");

        Path decrypted = Files.createTempFile("canonicalizer-test-", ".zip");
        try {
            String innerManifest = """
                    {
                      "documents":[{
                        "fileId":"abc-123",
                        "extension":".txt",
                        "originalName":"test.txt",
                        "hash":"%s"
                      }]
                    }
                    """.formatted(checksum);

            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(decrypted))) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(innerManifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                zip.putNextEntry(new ZipEntry("files/abc-123.txt"));
                zip.write(fileBytes);
                zip.closeEntry();
            }

            try (var canonical = canonicalizer.canonicalize(decrypted, header)) {
                var manifest = canonical.manifest();

                assertEquals(packageUuid.toString(), manifest.path("package_uuid").asText());
                assertEquals(
                        organizationId.toString(),
                        manifest.path("projects").get(0).path("organization_id").asText());

                var canonicalFile = manifest.path("projects").get(0).path("files").get(0);
                assertEquals("files/abc-123.txt", canonicalFile.path("object_key").asText());
                assertEquals(checksum, canonicalFile.path("checksum_sha256").asText());
                assertEquals(fileBytes.length, canonicalFile.path("size_bytes").asLong());
                assertFalse(canonicalFile.has("content_base64"));

                assertEquals(1, canonical.projectsCount());
                assertEquals(1, canonical.filesCount());
                assertTrue(canonical.sizeBytes() > fileBytes.length);
                assertEquals(64, canonical.manifestHash().length());
                assertEquals(64, canonical.packageHash().length());
            }
        } finally {
            Files.deleteIfExists(decrypted);
        }
    }
}
