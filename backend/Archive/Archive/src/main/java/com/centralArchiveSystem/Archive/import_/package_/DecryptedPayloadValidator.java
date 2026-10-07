package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DecryptedPayloadValidator {

    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_MANIFEST_BYTES = 10 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    @Value("${archive.packages.max-expanded-file-bytes:21474836480}")
    private long maxExpandedFileBytes = 20L * 1024 * 1024 * 1024;

    @Value("${archive.packages.max-expanded-total-bytes:42949672960}")
    private long maxExpandedTotalBytes = 40L * 1024 * 1024 * 1024;

    @Value("${archive.packages.max-archive-entries:100000}")
    private int maxArchiveEntries = 100_000;

    public DecryptedPayloadValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Inspection inspect(Path decryptedZip) throws Exception {
        if (decryptedZip == null || !Files.isRegularFile(decryptedZip)) {
            throw new IllegalArgumentException("Decrypted payload ZIP is missing");
        }

        byte[] manifestBytes = null;
        long expandedTotal = 0L;
        int entries = 0;
        Set<String> names = new HashSet<>();

        try (InputStream fileInput = Files.newInputStream(decryptedZip);
             ZipInputStream zip = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                entries++;
                if (entries > maxArchiveEntries) {
                    throw new IllegalArgumentException(
                            "Decrypted payload exceeds maximum archive entry count");
                }

                String name = normalizeEntryName(entry.getName());
                if (!names.add(name)) {
                    throw new IllegalArgumentException(
                            "Duplicate decrypted payload entry: " + name);
                }

                if ("manifest.json".equals(name)) {
                    manifestBytes = readLimited(zip, MAX_MANIFEST_BYTES);
                    expandedTotal += manifestBytes.length;
                } else {
                    long entryBytes = drainLimited(zip, name);
                    expandedTotal += entryBytes;
                }

                if (expandedTotal > maxExpandedTotalBytes) {
                    throw new IllegalArgumentException(
                            "Decrypted payload exceeds maximum expanded size");
                }
                zip.closeEntry();
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException(
                    "Decrypted payload is not a valid ZIP archive", e);
        }

        if (entries == 0) {
            throw new IllegalArgumentException("Decrypted payload ZIP is empty");
        }
        if (manifestBytes == null) {
            throw new IllegalArgumentException(
                    "Decrypted payload ZIP has no manifest.json at its root");
        }

        JsonNode manifest = objectMapper.readTree(manifestBytes);
        if (manifest == null || !manifest.isObject()) {
            throw new IllegalArgumentException(
                    "Decrypted payload manifest.json must contain a JSON object");
        }

        return new Inspection(
                sha256(manifestBytes),
                new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8),
                entries,
                expandedTotal
        );
    }

    private long drainLimited(InputStream input, String entryName) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            size += read;
            if (size > maxExpandedFileBytes) {
                throw new IllegalArgumentException(
                        "Decrypted payload entry exceeds maximum expanded size: " + entryName);
            }
        }
        return size;
    }

    private byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException(
                        "Decrypted manifest.json exceeds maximum allowed size");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String normalizeEntryName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Decrypted payload entry path is missing");
        }
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Decrypted payload entry path is missing");
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "Unsafe decrypted payload entry path: " + value);
            }
        }
        return normalized;
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    public record Inspection(
            String manifestHash,
            String manifestJson,
            int entriesCount,
            long expandedBytes
    ) {}
}
