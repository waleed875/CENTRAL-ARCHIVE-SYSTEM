package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SealedPackageInspector {

    private static final String HEADER_ENTRY = "header.json";
    private static final String PAYLOAD_ENTRY = "payload.enc";
    private static final String FORMAT = "omnivault/nxpkg";
    private static final int MAX_HEADER_BYTES = 10 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;
    private static final byte[] ED25519_X509_PREFIX = hex("302a300506032b6570032100");

    private final ObjectMapper objectMapper;

    public SealedPackageInspector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean supportsFilename(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".nxpkg");
    }

    public Inspection inspect(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Package file is required");
        }
        if (!supportsFilename(file.getOriginalFilename())) {
            throw new IllegalArgumentException("Encrypted package must use .zip or .nxpkg");
        }
        String packageHash;
        try (InputStream input = file.getInputStream()) {
            packageHash = sha256(input);
        }
        try (InputStream input = file.getInputStream()) {
            return inspect(input, packageHash);
        }
    }

    public Inspection inspect(InputStream packageInput, String packageHash) throws Exception {
        byte[] headerBytes = null;
        String payloadHash = null;
        long payloadSize = -1L;
        List<String> entries = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(packageInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String name = normalizeRootName(entry.getName());
                if (entries.contains(name)) {
                    throw new IllegalArgumentException("Duplicate encrypted package entry: " + name);
                }
                entries.add(name);
                if (HEADER_ENTRY.equals(name)) {
                    headerBytes = readLimited(zip, MAX_HEADER_BYTES);
                } else if (PAYLOAD_ENTRY.equals(name)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long size = 0L;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        size += read;
                    }
                    payloadHash = toHex(digest.digest());
                    payloadSize = size;
                } else {
                    throw new IllegalArgumentException(
                            "Encrypted package may contain only header.json and payload.enc");
                }
                zip.closeEntry();
            }
        }

        if (headerBytes == null) {
            throw new IllegalArgumentException("header.json was not found in the encrypted package");
        }
        if (payloadHash == null) {
            throw new IllegalArgumentException("payload.enc was not found in the encrypted package");
        }
        if (entries.size() != 2) {
            throw new IllegalArgumentException(
                    "Encrypted package must contain exactly header.json and payload.enc");
        }

        JsonNode header = objectMapper.readTree(headerBytes);
        if (header == null || !header.isObject()) {
            throw new IllegalArgumentException("header.json must contain a JSON object");
        }

        String format = requiredText(header, "format");
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("Unsupported encrypted package format: " + format);
        }
        int version = requiredPositiveInt(header, "version");
        if (version < 3 || version > 6) {
            throw new IllegalArgumentException("Unsupported encrypted package version: " + version);
        }

        UUID packageUuid = requiredUuid(header, "package_uuid");
        UUID sourceTerminalUuid = requiredUuid(header, "source_terminal_uuid");
        UUID sourceOrganizationId = optionalUuid(header, "source_organization_id");
        String sourceUsername = optionalText(header, "source_username");
        String schemaVersion = requiredText(header, "schema_version");
        String packageName = optionalText(header, "package_name");

        JsonNode encryption = header.path("encryption");
        if (!encryption.isObject()) {
            throw new IllegalArgumentException("Encrypted package has no encryption metadata");
        }
        if (!"AES-256-GCM".equals(requiredText(encryption, "algorithm"))) {
            throw new IllegalArgumentException("Unsupported package encryption algorithm");
        }
        if (!"PBKDF2-HMAC-SHA256".equals(requiredText(encryption, "kdf"))) {
            throw new IllegalArgumentException("Unsupported package KDF");
        }
        if (requiredPositiveInt(encryption, "iterations") < 100_000) {
            throw new IllegalArgumentException("Package KDF iteration count is too low");
        }
        if (version >= 3 && requiredPositiveInt(encryption, "chunkSize") <= 0) {
            throw new IllegalArgumentException("Encrypted package chunkSize is required");
        }
        decodeBase64(requiredText(encryption, "salt"), "encryption.salt");

        String declaredPayloadHash = requiredText(header, "payloadHash").toLowerCase(Locale.ROOT);
        if (!declaredPayloadHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadHash must be a SHA-256 hex digest");
        }
        boolean payloadHashValid = declaredPayloadHash.equals(payloadHash);
        if (!payloadHashValid) {
            throw new IllegalArgumentException("Encrypted package payloadHash does not match payload.enc");
        }

        JsonNode origin = header.path("origin");
        if (!origin.isObject()) {
            throw new IllegalArgumentException("Encrypted package has no origin metadata");
        }
        UUID originTerminalUuid = parseUuid(requiredText(origin, "terminalUuid"), "origin.terminalUuid");
        if (!sourceTerminalUuid.equals(originTerminalUuid)) {
            throw new IllegalArgumentException("origin.terminalUuid does not match source_terminal_uuid");
        }
        if (!"Ed25519".equals(requiredText(origin, "algorithm"))) {
            throw new IllegalArgumentException("Unsupported package signature algorithm");
        }

        byte[] publicKey = decodeBase64(requiredText(origin, "publicKey"), "origin.publicKey");
        if (publicKey.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        String computedFingerprint = fingerprint(publicKey);
        String declaredFingerprint = requiredText(origin, "fingerprint").toLowerCase(Locale.ROOT);
        if (!computedFingerprint.equals(declaredFingerprint)) {
            throw new IllegalArgumentException("Origin fingerprint does not match the public key");
        }

        byte[] signature = decodeBase64(requiredText(header, "signature"), "signature");
        boolean signatureValid = verifyEd25519(publicKey, signature, canonicalBytes(header));
        if (!signatureValid) {
            throw new IllegalArgumentException("Encrypted package header signature is invalid");
        }

        JsonNode projects = header.path("projects");
        if (!projects.isArray()) {
            throw new IllegalArgumentException("Encrypted package projects must be an array");
        }
        int filesCount = 0;
        for (JsonNode project : projects) {
            if (!project.isObject()) {
                throw new IllegalArgumentException("Encrypted package project metadata must be objects");
            }
            requiredUuid(project, "project_id");
            requiredText(project, "project_number");
            requiredText(project, "name");
            int year = requiredPositiveInt(project, "year");
            if (year < 1900 || year > 2100) {
                throw new IllegalArgumentException("Project year is out of range: " + year);
            }
            JsonNode files = project.path("files");
            if (files.isArray()) {
                filesCount += files.size();
            } else if (!files.isMissingNode() && !files.isNull()) {
                throw new IllegalArgumentException("Project files must be an array");
            }
        }

        return new Inspection(
                packageHash,
                sha256(headerBytes),
                new String(headerBytes, java.nio.charset.StandardCharsets.UTF_8),
                header,
                packageUuid,
                sourceOrganizationId,
                sourceTerminalUuid,
                sourceUsername,
                packageName,
                schemaVersion,
                format,
                version,
                payloadHash,
                payloadSize,
                payloadHashValid,
                signatureValid,
                computedFingerprint,
                projects.size(),
                filesCount
        );
    }

    private boolean verifyEd25519(byte[] rawPublicKey, byte[] signatureBytes, byte[] message)
            throws Exception {
        byte[] encoded = new byte[ED25519_X509_PREFIX.length + rawPublicKey.length];
        System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
        System.arraycopy(rawPublicKey, 0, encoded, ED25519_X509_PREFIX.length, rawPublicKey.length);
        var publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(encoded));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(message);
        return verifier.verify(signatureBytes);
    }

    private byte[] canonicalBytes(JsonNode header) throws Exception {
        ObjectNode copy = ((ObjectNode) header).deepCopy();
        copy.remove("signature");
        return objectMapper.writeValueAsBytes(sortNode(copy));
    }

    private JsonNode sortNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                sorted.set(name, sortNode(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                array.add(sortNode(child));
            }
            return array;
        }
        return node;
    }

    private String normalizeRootName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Encrypted package entry path is missing");
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.contains("/")) {
            throw new IllegalArgumentException("Unsafe encrypted package entry path: " + value);
        }
        return normalized;
    }

    private byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException("header.json exceeds the maximum allowed size");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
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

    private UUID requiredUuid(JsonNode node, String field) {
        return parseUuid(requiredText(node, field), field);
    }

    private UUID optionalUuid(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value == null ? null : parseUuid(value, field);
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID in " + field + ": " + value);
        }
    }

    private byte[] decodeBase64(String value, String field) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 in " + field);
        }
    }

    private String fingerprint(byte[] rawPublicKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawPublicKey);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                result.append(':');
            }
            result.append(String.format("%02x", digest[i]));
        }
        return result.toString();
    }

    private String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    private String sha256(byte[] input) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    public record Inspection(
            String packageHash,
            String headerHash,
            String headerJson,
            JsonNode header,
            UUID packageUuid,
            UUID sourceOrganizationId,
            UUID sourceTerminalUuid,
            String sourceUsername,
            String packageName,
            String schemaVersion,
            String format,
            int version,
            String payloadHash,
            long payloadSizeBytes,
            boolean payloadHashValid,
            boolean signatureValid,
            String terminalFingerprint,
            int projectsCount,
            int filesCount
    ) {}
}
