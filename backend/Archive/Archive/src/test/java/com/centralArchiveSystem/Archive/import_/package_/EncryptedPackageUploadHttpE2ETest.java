package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

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
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EncryptedPackageUploadHttpE2ETest {

    private static final String PACKAGE_PASSPHRASE = "Encrypted-Package-123!";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired BranchPackageRepository packageRepository;

    private String username;
    private final String loginPassword = "EncryptedUploadLogin123!";

    @BeforeEach
    void setupUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "encrypted_pkg_" + suffix;

        Role importOfficer = roleRepository.findByCode("import_officer").orElseThrow();

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(loginPassword));
        user.setFullName("Encrypted Package Upload");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(Set.of(importOfficer));
        userRepository.save(user);
    }

    @Test
    void encryptedPackageIsDecryptedDuringReceiveAndPassphraseIsNotReturned() throws Exception {
        String token = login();
        Fixture fixture = fixture(PACKAGE_PASSPHRASE);

        MockMultipartFile packageFile = new MockMultipartFile(
                "package_file",
                "branch-package.zip",
                "application/zip",
                fixture.outerZip()
        );

        String response = mockMvc.perform(multipart("/api/v1/packages/receive")
                        .file(packageFile)
                        .param("passphrase", PACKAGE_PASSPHRASE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.encrypted").value(true))
                .andExpect(jsonPath("$.data.signatureValid").value(true))
                .andExpect(jsonPath("$.data.decryptedPayloadHash").isNotEmpty())
                .andExpect(jsonPath("$.data.decryptedPayloadSizeBytes").isNumber())
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains(PACKAGE_PASSPHRASE));

        JsonNode body = mapper.readTree(response);
        UUID id = UUID.fromString(body.path("data").path("id").asText());
        BranchPackage pkg = packageRepository.findById(id).orElseThrow();

        assertEquals(BranchPackage.PackageStatus.VERIFIED, pkg.getStatus());
        assertNotNull(pkg.getManifestData());
        assertNotNull(pkg.getManifestHash());
        assertNotNull(pkg.getDecryptedPayloadHash());
        assertTrue(pkg.getDecryptedPayloadSizeBytes() > 0);
        assertFalse(pkg.getHeaderData().contains(PACKAGE_PASSPHRASE));
        assertFalse(pkg.getManifestData().contains(PACKAGE_PASSPHRASE));
    }

    @Test
    void wrongPassphraseIsRejectedAndPackageRowIsNotCreated() throws Exception {
        String token = login();
        Fixture fixture = fixture(PACKAGE_PASSPHRASE);
        long before = packageRepository.count();

        MockMultipartFile packageFile = new MockMultipartFile(
                "package_file",
                "wrong-passphrase.nxpkg",
                "application/zip",
                fixture.outerZip()
        );

        mockMvc.perform(multipart("/api/v1/packages/receive")
                        .file(packageFile)
                        .param("passphrase", "wrong-passphrase")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertEquals(before, packageRepository.count());
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, loginPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("token").asText();
    }

    private Fixture fixture(String passphrase) throws Exception {
        UUID packageUuid = UUID.randomUUID();
        UUID terminalUuid = UUID.randomUUID();
        UUID sourceOrg = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        byte[] innerZip = innerZip(packageUuid, terminalUuid, sourceOrg, projectId);
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) (20 + i);
        int iterations = 210000;
        int chunkSize = 4 * 1024 * 1024;
        byte[] sealed = encryptFrames(innerZip, passphrase, salt, iterations, chunkSize);

        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] rawPublicKey = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);

        ObjectNode header = mapper.createObjectNode();
        header.put("package_uuid", packageUuid.toString());
        header.put("package_name", "branch-package.zip");
        header.put("schema_version", "1.0");
        header.put("source_organization_id", sourceOrg.toString());
        header.put("source_terminal_uuid", terminalUuid.toString());
        header.put("source_username", "branch@example.test");

        ObjectNode project = header.putArray("projects").addObject();
        project.put("project_id", projectId.toString());
        project.put("project_number", "ENC-UPLOAD-1");
        project.put("name", "Encrypted upload project");
        project.put("year", 2026);
        project.putArray("members");
        project.putArray("files");

        header.put("format", "omnivault/nxpkg");
        header.put("version", 6);
        header.put("generatedAt", "2026-10-07T12:00:00");

        ObjectNode encryption = header.putObject("encryption");
        encryption.put("algorithm", "AES-256-GCM");
        encryption.put("kdf", "PBKDF2-HMAC-SHA256");
        encryption.put("iterations", iterations);
        encryption.put("salt", Base64.getEncoder().encodeToString(salt));
        encryption.put("chunkSize", chunkSize);

        header.put("payloadHash", hex(MessageDigest.getInstance("SHA-256").digest(sealed)));

        ObjectNode origin = header.putObject("origin");
        origin.put("terminalUuid", terminalUuid.toString());
        origin.put("publicKey", Base64.getEncoder().encodeToString(rawPublicKey));
        origin.put("algorithm", "Ed25519");
        origin.put("fingerprint", fingerprint(rawPublicKey));

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(mapper.writeValueAsBytes(sortNode(header)));
        header.put("signature", Base64.getEncoder().encodeToString(signer.sign()));

        return new Fixture(packageUuid, outerZip(mapper.writeValueAsBytes(header), sealed));
    }

    private byte[] innerZip(UUID packageUuid,
                            UUID terminalUuid,
                            UUID sourceOrg,
                            UUID projectId) throws Exception {
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
                    "project_number":"ENC-UPLOAD-1",
                    "name":"Encrypted upload project",
                    "year":2026,
                    "files":[]
                  }]
                }
                """.formatted(packageUuid, terminalUuid, sourceOrg, projectId, sourceOrg);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
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
                byte[] aad = ByteBuffer.allocate(5)
                        .putInt(frame)
                        .put((byte) (last ? 1 : 0))
                        .array();

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce)
                );
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

    private record Fixture(UUID packageUuid, byte[] outerZip) {}
}
