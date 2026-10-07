package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SealedPackageInspectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SealedPackageInspector inspector = new SealedPackageInspector(mapper);

    @Test
    void acceptsSignedV6EnvelopeWithoutDecryptingPayload() throws Exception {
        Fixture fixture = packageFixture("ciphertext-frame".getBytes());

        var inspection = inspector.inspect(new MockMultipartFile(
                "package_file", "test.zip", "application/zip", fixture.zip()));

        assertEquals("omnivault/nxpkg", inspection.format());
        assertEquals(6, inspection.version());
        assertEquals(fixture.packageUuid(), inspection.packageUuid());
        assertTrue(inspection.payloadHashValid());
        assertTrue(inspection.signatureValid());
        assertEquals(1, inspection.projectsCount());
        assertEquals(1, inspection.filesCount());
        assertEquals("test.zip", inspection.packageName());
        assertEquals("1.0", inspection.schemaVersion());
    }

    @Test
    void rejectsPayloadThatDoesNotMatchSignedHeaderHash() throws Exception {
        Fixture fixture = packageFixture("original-payload".getBytes());
        byte[] changedZip = zip(fixture.headerBytes(), "changed-payload".getBytes());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> inspector.inspect(new MockMultipartFile(
                        "package_file", "tampered.nxpkg", "application/zip", changedZip))
        );
        assertTrue(error.getMessage().contains("payloadHash"));
    }

    private Fixture packageFixture(byte[] payload) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] rawPublicKey = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);

        UUID packageUuid = UUID.randomUUID();
        UUID terminalUuid = UUID.randomUUID();

        ObjectNode header = mapper.createObjectNode();
        header.put("package_uuid", packageUuid.toString());
        header.put("package_name", "test.zip");
        header.put("schema_version", "1.0");
        header.put("source_organization_id", UUID.randomUUID().toString());
        header.put("source_terminal_uuid", terminalUuid.toString());
        header.put("source_username", "branch@example.test");

        ArrayNode projects = header.putArray("projects");
        ObjectNode project = projects.addObject();
        project.put("project_id", UUID.randomUUID().toString());
        project.put("project_number", "1");
        project.put("name", "Encrypted test project");
        project.put("year", 2026);
        project.putArray("members");
        ObjectNode file = project.putArray("files").addObject();
        file.put("original_name", "test.txt");
        file.put("extension", "txt");
        file.put("size_bytes", 0);
        file.put("checksum_sha256",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        header.put("format", "omnivault/nxpkg");
        header.put("version", 6);
        header.put("generatedAt", "2026-10-07T12:00:00");

        ObjectNode encryption = header.putObject("encryption");
        encryption.put("algorithm", "AES-256-GCM");
        encryption.put("kdf", "PBKDF2-HMAC-SHA256");
        encryption.put("iterations", 210000);
        encryption.put("salt", Base64.getEncoder().encodeToString(new byte[16]));
        encryption.put("chunkSize", 4194304);

        header.put("payloadHash", hex(MessageDigest.getInstance("SHA-256").digest(payload)));

        ObjectNode origin = header.putObject("origin");
        origin.put("terminalUuid", terminalUuid.toString());
        origin.put("publicKey", Base64.getEncoder().encodeToString(rawPublicKey));
        origin.put("algorithm", "Ed25519");
        origin.put("fingerprint", fingerprint(rawPublicKey));

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(mapper.writeValueAsBytes(sortNode(header)));
        header.put("signature", Base64.getEncoder().encodeToString(signer.sign()));

        byte[] headerBytes = mapper.writeValueAsBytes(header);
        return new Fixture(packageUuid, headerBytes, zip(headerBytes, payload));
    }

    private byte[] zip(byte[] header, byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("header.json"));
            zip.write(header);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payload.enc"));
            zip.write(payload);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private JsonNode sortNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                sorted.set(name, sortNode(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            node.forEach(child -> sorted.add(sortNode(child)));
            return sorted;
        }
        return node;
    }

    private String fingerprint(byte[] publicKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) value.append(':');
            value.append(String.format("%02x", digest[i]));
        }
        return value.toString();
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b));
        return value.toString();
    }

    private record Fixture(UUID packageUuid, byte[] headerBytes, byte[] zip) {}
}
