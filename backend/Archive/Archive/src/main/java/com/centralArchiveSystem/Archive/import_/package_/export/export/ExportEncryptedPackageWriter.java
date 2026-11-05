package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Emit the signed omnivault/nxpkg envelope already accepted by BranchPackageService.
 * Data is encrypted in 1MiB AES-256-GCM frames with a random PBKDF2 salt and random
 * nonce per frame. Ed25519 uses an ephemeral signing key for package integrity only;
 * it is NOT a verified exporter identity or a trusted organization certificate.
 */
@Component
public class ExportEncryptedPackageWriter {
    private static final int CHUNK = 1024 * 1024;
    private static final int ITERATIONS = 230_000;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper;

    public ExportEncryptedPackageWriter(ObjectMapper mapper) { this.mapper = mapper; }

    public void seal(Path innerPkg, Path outerPkg, JsonNode manifest, String passphrase) throws Exception {
        if (passphrase == null || passphrase.length() < 12) {
            throw new IllegalArgumentException("مطلوب كلمة مرور قوية لتشفير الحزمة");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] passwordBytes = derive(passphrase, salt);
        Path sealed = Files.createTempFile("archive-export-sealed-", ".enc");
        try {
            MessageDigest cipherDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = Files.newInputStream(innerPkg);
                 OutputStream stream = Files.newOutputStream(sealed);
                 DataOutputStream target = new DataOutputStream(stream)) {
                byte[] plain = new byte[CHUNK];
                int index = 0;
                int n = readChunk(source, plain);
                if (n < 1) throw new IllegalStateException("Inner PKG is empty");
                while (n > 0) {
                    byte[] next = new byte[CHUNK];
                    int nextLength = readChunk(source, next);
                    boolean last = nextLength == 0;
                    byte[] nonce = new byte[12];
                    random.nextBytes(nonce);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(passwordBytes, "AES"),
                            new GCMParameterSpec(128, nonce));
                    cipher.updateAAD(ByteBuffer.allocate(5).putInt(index).put((byte) (last ? 1 : 0)).array());
                    byte[] encrypted = cipher.doFinal(plain, 0, n);
                    byte[] prefix = ByteBuffer.allocate(4).putInt(n).array();
                    target.write(prefix); target.write(nonce); target.write(encrypted);
                    cipherDigest.update(prefix); cipherDigest.update(nonce); cipherDigest.update(encrypted);
                    Arrays.fill(plain, (byte) 0);
                    plain = next;
                    n = nextLength;
                    index++;
                }
            }

            KeyPair signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] pub = signing.getPublic().getEncoded();
            byte[] raw = Arrays.copyOfRange(pub, pub.length - 32, pub.length);
            byte[] identity = MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder fingerprint = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0) fingerprint.append(':');
                fingerprint.append(String.format("%02x", identity[i]));
            }

            ObjectNode header = mapper.createObjectNode();
            for (String required : List.of("package_uuid", "package_name", "schema_version",
                    "source_terminal_uuid")) header.set(required, manifest.path(required).deepCopy());
            for (String optional : List.of("source_organization_id", "source_username")) {
                if (manifest.hasNonNull(optional)) header.set(optional, manifest.get(optional).deepCopy());
            }
            header.set("projects", manifest.path("projects").deepCopy());
            header.put("format", "omnivault/nxpkg");
            header.put("version", 6);
            header.put("generatedAt", java.time.Instant.now().toString());
            ObjectNode encryption = header.putObject("encryption");
            encryption.put("algorithm", "AES-256-GCM");
            encryption.put("kdf", "PBKDF2-HMAC-SHA256");
            encryption.put("iterations", ITERATIONS);
            encryption.put("salt", Base64.getEncoder().encodeToString(salt));
            encryption.put("chunkSize", CHUNK);
            header.put("payloadHash", HexFormat.of().formatHex(cipherDigest.digest()));
            ObjectNode origin = header.putObject("origin");
            origin.put("terminalUuid", manifest.path("source_terminal_uuid").asText());
            origin.put("publicKey", Base64.getEncoder().encodeToString(raw));
            origin.put("algorithm", "Ed25519");
            origin.put("fingerprint", fingerprint.toString());
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(signing.getPrivate());
            signature.update(mapper.writeValueAsBytes(sorted(header)));
            header.put("signature", Base64.getEncoder().encodeToString(signature.sign()));

            try (OutputStream output = Files.newOutputStream(outerPkg);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("header.json"));
                zip.write(mapper.writeValueAsBytes(header));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("payload.enc"));
                try (InputStream input = Files.newInputStream(sealed)) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Files.deleteIfExists(sealed);
        }
    }

    private static int readChunk(InputStream input, byte[] buffer) throws Exception {
        int result = 0;
        while (result < buffer.length) {
            int n = input.read(buffer, result, buffer.length - result);
            if (n == -1) break;
            if (n == 0) continue;
            result += n;
        }
        return result;
    }

    private static byte[] derive(String passphrase, byte[] salt) throws Exception {
        char[] chars = passphrase.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = ((ObjectNode) node).objectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) out.set(key, sorted(node.get(key)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = ((ArrayNode) node).arrayNode();
            for (JsonNode child : node) out.add(sorted(child));
            return out;
        }
        return node;
    }
}
