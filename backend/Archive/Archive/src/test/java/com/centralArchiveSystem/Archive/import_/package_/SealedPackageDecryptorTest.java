package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

class SealedPackageDecryptorTest {

    private static final String PASSPHRASE = "PackagePassphrase-123!";
    private final ObjectMapper mapper = new ObjectMapper();
    private final SealedPackageInspector inspector = new SealedPackageInspector(mapper);
    private final SealedPackageDecryptor decryptor = new SealedPackageDecryptor();
    private final DecryptedPayloadValidator validator = new DecryptedPayloadValidator(mapper);

    @Test
    void decryptsSignedV6PayloadAndFindsInnerManifest() throws Exception {
        Fixture fixture = fixture(PASSPHRASE);
        MockMultipartFile packageFile = new MockMultipartFile(
                "package_file", "encrypted.zip", "application/zip", fixture.outerZip());

        var inspection = inspector.inspect(packageFile);
        try (var decrypted = decryptor.decrypt(packageFile, inspection, PASSPHRASE)) {
            var inner = validator.inspect(decrypted.path());
            JsonNode manifest = mapper.readTree(inner.manifestJson());

            assertEquals(fixture.packageUuid().toString(), manifest.path("package_uuid").asText());
            assertEquals(1, manifest.path("projects").size());
            assertTrue(decrypted.sizeBytes() > 0);
            assertEquals(64, decrypted.sha256().length());
        }
    }

    @Test
    void rejectsWrongPassphraseWithoutProducingPlaintext() throws Exception {
        Fixture fixture = fixture(PASSPHRASE);
        MockMultipartFile packageFile = new MockMultipartFile(
                "package_file", "encrypted.nxpkg", "application/zip", fixture.outerZip());

        var inspection = inspector.inspect(packageFile);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> decryptor.decrypt(packageFile, inspection, "wrong-passphrase")
        );
        assertEquals("Package passphrase is incorrect", error.getMessage());
    }

    private Fixture fixture(String passphrase) throws Exception {
        UUID packageUuid = UUID.randomUUID();
        UUID terminalUuid = UUID.randomUUID();
        UUID sourceOrg = UUID.randomUUID();

        byte[] fileBytes = "hello encrypted archive".getBytes(StandardCharsets.UTF_8);
        byte[] innerZip = innerZip(packageUuid, terminalUuid, sourceOrg, fileBytes);

        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) (i + 1);
        int iterations = 210000;
        int chunkSize = 4 * 1024 * 1024;
        byte[] sealedPayload = encryptFrames(innerZip, passphrase, salt, iterations, chunkSize);

        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] rawPublicKey = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);

        ObjectNode header = mapper.createObjectNode();
        header.put("package_uuid", packageUuid.toString());
        header.put("package_name", "encrypted.zip");
        header.put("schema_version", "1.0");
        header.put("source_organization_id", sourceOrg.toString());
        header.put("source_terminal_uuid", terminalUuid.toString());
        header.put("source_username", "branch@example.test");

        ArrayNode projects = header.putArray("projects");
        ObjectNode project = projects.addObject();
        project.put("project_id", UUID.randomUUID().toString());
        project.put("project_number", "ENC-1");
        project.put("name", "Encrypted project");
        project.put("year", 2026);
        project.putArray("members");
        ObjectNode file = project.putArray("files").addObject();
        file.put("project_id", project.path("project_id").asText());
        file.put("original_name", "document.txt");
        file.put("extension", "txt");
        file.put("size_bytes", fileBytes.length);
        file.put("checksum_sha256", hex(MessageDigest.getInstance("SHA-256").digest(fileBytes)));
        file.put("object_key", "files/document.txt");

        header.put("format", "omnivault/nxpkg");
        header.put("version", 6);
        header.put("generatedAt", "2026-10-07T12:00:00");

        ObjectNode encryption = header.putObject("encryption");
        encryption.put("algorithm", "AES-256-GCM");
        encryption.put("kdf", "PBKDF2-HMAC-SHA256");
        encryption.put("iterations", iterations);
        encryption.put("salt", Base64.getEncoder().encodeToString(salt));
        encryption.put("chunkSize", chunkSize);

        header.put("payloadHash", hex(MessageDigest.getInstance("SHA-256").digest(sealedPayload)));

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
        return new Fixture(packageUuid, outerZip(headerBytes, sealedPayload));
    }

    private byte[] innerZip(UUID packageUuid,
                            UUID terminalUuid,
                            UUID sourceOrg,
                            byte[] fileBytes) throws Exception {
        String manifest = """
                {
                  "package_uuid":"%s",
                  "package_name":"inner.pkg",
                  "schema_version":"1.0",
                  "source_terminal_uuid":"%s",
                  "source_organization_id":"%s",
                  "source_username":"branch",
                  "projects":[{
                    "project_id":"%s",
                    "organization_id":"%s",
                    "project_number":"ENC-1",
                    "name":"Encrypted project",
                    "year":2026,
                    "files":[{
                      "project_id":"%s",
                      "original_name":"document.txt",
                      "extension":"txt",
                      "object_key":"files/document.txt",
                      "size_bytes":%d,
                      "checksum_sha256":"%s"
                    }]
                  }]
                }
                """.formatted(
                packageUuid,
                terminalUuid,
                sourceOrg,
                UUID.randomUUID(),
                sourceOrg,
                UUID.randomUUID(),
                fileBytes.length,
                hex(MessageDigest.getInstance("SHA-256").digest(fileBytes))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("files/document.txt"));
            zip.write(fileBytes);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] encryptFrames(byte[] plaintext,
                                 String passphrase,
                                 byte[] salt,
                                 int iterations,
                                 int chunkSize) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int offset = 0;
            int frame = 0;
            while (offset < plaintext.length) {
                int length = Math.min(chunkSize, plaintext.length - offset);
                boolean last = offset + length >= plaintext.length;
                byte[] nonce = new byte[12];
                ByteBuffer.wrap(nonce).putLong(4, frame + 1L);
                byte[] aad = ByteBuffer.allocate(5).putInt(frame).put((byte) (last ? 1 : 0)).array();

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
                cipher.updateAAD(aad);
                byte[] encrypted = cipher.doFinal(plaintext, offset, length);

                out.write(ByteBuffer.allocate(4).putInt(length).array());
                out.write(nonce);
                out.write(encrypted);
                offset += length;
                frame++;
            }
            return out.toByteArray();
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            spec.clearPassword();
        }
    }

    private byte[] outerZip(byte[] header, byte[] payload) throws Exception {
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
            for (String name : names) sorted.set(name, sortNode(node.get(name)));
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

    private record Fixture(UUID packageUuid, byte[] outerZip) {}
}
