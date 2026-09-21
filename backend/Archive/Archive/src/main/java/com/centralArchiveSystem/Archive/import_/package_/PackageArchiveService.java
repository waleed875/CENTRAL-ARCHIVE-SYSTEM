package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.project.file.FileContentInspector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class PackageArchiveService {

    private static final String MANIFEST_FILE_NAME = "manifest.json";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_MANIFEST_BYTES = 10 * 1024 * 1024;
    private static final long DEFAULT_MAX_EXPANDED_FILE_BYTES = 20L * 1024 * 1024 * 1024;
    private static final long DEFAULT_MAX_EXPANDED_TOTAL_BYTES = 40L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_ARCHIVE_ENTRIES = 100_000;

    private final ObjectMapper objectMapper;

    @Value("${archive.packages.max-expanded-file-bytes:21474836480}")
    private long maxExpandedFileBytes = DEFAULT_MAX_EXPANDED_FILE_BYTES;

    @Value("${archive.packages.max-expanded-total-bytes:42949672960}")
    private long maxExpandedTotalBytes = DEFAULT_MAX_EXPANDED_TOTAL_BYTES;

    @Value("${archive.packages.max-archive-entries:100000}")
    private int maxArchiveEntries = DEFAULT_MAX_ARCHIVE_ENTRIES;
    private final Set<String> supportedSchemaVersions;

    public PackageArchiveService(
            ObjectMapper objectMapper,
            @Value("${archive.packages.supported-schema-versions:1.0}") String supportedSchemaVersions) {
        this.objectMapper = objectMapper;
        this.supportedSchemaVersions = new HashSet<>();
        for (String version : supportedSchemaVersions.split(",")) {
            if (!version.isBlank()) {
                this.supportedSchemaVersions.add(version.trim());
            }
        }
    }

    public Inspection inspect(MultipartFile packageFile) throws Exception {
        if (packageFile == null || packageFile.isEmpty()) {
            throw new IllegalArgumentException("Package file is required");
        }

        String originalName = packageFile.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(".pkg")) {
            throw new IllegalArgumentException("Package file must use the .pkg extension");
        }

        String packageHash;
        try (InputStream input = packageFile.getInputStream()) {
            packageHash = sha256(input);
        }

        byte[] manifestBytes;
        try (InputStream input = packageFile.getInputStream()) {
            manifestBytes = readManifestBytes(input);
        }

        JsonNode manifest = objectMapper.readTree(manifestBytes);
        if (manifest == null || !manifest.isObject()) {
            throw new IllegalArgumentException("manifest.json must contain a JSON object");
        }

        return new Inspection(
                packageHash,
                sha256(manifestBytes),
                new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8),
                manifest,
                packageFile.getSize()
        );
    }

    public Verification verifyContents(InputStream packageInput,
                                       String expectedManifestHash,
                                       String expectedSchemaVersion) throws Exception {
        Map<String, EntryScan> actualEntries = new LinkedHashMap<>();
        byte[] manifestBytes = null;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        long totalExpandedBytes = 0L;
        int archiveEntries = 0;

        try (ZipInputStream zip = new ZipInputStream(packageInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                archiveEntries++;
                if (archiveEntries > maxArchiveEntries) {
                    throw new IllegalArgumentException(
                            "Package exceeds maximum archive entry count: " + maxArchiveEntries);
                }
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                String entryName = normalizeEntryName(entry.getName());
                if (actualEntries.containsKey(entryName)) {
                    errors.add("Duplicate archive entry: " + entryName);
                    drain(zip);
                    zip.closeEntry();
                    continue;
                }

                if (isManifestEntry(entryName)) {
                    manifestBytes = readLimited(zip, MAX_MANIFEST_BYTES);
                    actualEntries.put(
                            entryName,
                            new EntryScan(
                                    sha256(manifestBytes),
                                    manifestBytes.length,
                                    firstBytes(manifestBytes, FileContentInspector.SNIFF_BYTES),
                                    null
                            )
                    );
                } else {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[BUFFER_SIZE];
                    ByteArrayOutputStream prefix = new ByteArrayOutputStream(FileContentInspector.SNIFF_BYTES);
                    Path zipSpool = null;
                    OutputStream spoolOutput = null;
                    long size = 0;
                    int read;

                    try {
                        while ((read = zip.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                            size += read;
                            totalExpandedBytes += read;

                            if (size > maxExpandedFileBytes) {
                                throw new IllegalArgumentException(
                                        "Package entry exceeds maximum expanded size: " + entryName);
                            }
                            if (totalExpandedBytes > maxExpandedTotalBytes) {
                                throw new IllegalArgumentException(
                                        "Package exceeds maximum total expanded size");
                            }

                            int remaining = FileContentInspector.SNIFF_BYTES - prefix.size();
                            if (remaining > 0) {
                                prefix.write(buffer, 0, Math.min(read, remaining));
                            }

                            if (zipSpool == null
                                    && FileContentInspector.hasZipSignature(prefix.toByteArray())) {
                                zipSpool = Files.createTempFile("central-archive-package-entry-", ".zip");
                                spoolOutput = Files.newOutputStream(zipSpool);
                                spoolOutput.write(prefix.toByteArray());
                            } else if (spoolOutput != null) {
                                spoolOutput.write(buffer, 0, read);
                            }
                        }
                    } catch (Exception e) {
                        if (spoolOutput != null) {
                            try {
                                spoolOutput.close();
                            } catch (IOException closeError) {
                                e.addSuppressed(closeError);
                            }
                            spoolOutput = null;
                        }
                        if (zipSpool != null) {
                            try {
                                Files.deleteIfExists(zipSpool);
                            } catch (IOException cleanupError) {
                                e.addSuppressed(cleanupError);
                            }
                        }
                        throw e;
                    } finally {
                        if (spoolOutput != null) {
                            spoolOutput.close();
                        }
                    }

                    actualEntries.put(
                            entryName,
                            new EntryScan(
                                    toHex(digest.digest()),
                                    size,
                                    prefix.toByteArray(),
                                    zipSpool
                            )
                    );
                }
                zip.closeEntry();
            }
        }

        try {
            if (manifestBytes == null) {
                errors.add("manifest.json was not found in the package");
                return new Verification(false, false, false, null, 0, 0, 0L, errors, warnings, null);
            }
    
            String actualManifestHash = sha256(manifestBytes);
            boolean manifestHashValid = actualManifestHash.equalsIgnoreCase(expectedManifestHash);
            if (!manifestHashValid) {
                errors.add("Manifest hash mismatch");
            }
    
            JsonNode manifest = objectMapper.readTree(manifestBytes);
            String schemaVersion = manifest.path("schema_version").asText("");
            boolean schemaVersionValid = !schemaVersion.isBlank()
                    && schemaVersion.equals(expectedSchemaVersion)
                    && isSchemaSupported(schemaVersion);
            if (!schemaVersionValid) {
                errors.add("Unsupported or inconsistent schema_version: " + schemaVersion);
            }
    
            Map<String, ExpectedFile> expectedFiles = expectedFiles(manifest, errors);
            boolean filesValid = true;
            long verifiedFileBytes = 0L;
    
            for (Map.Entry<String, ExpectedFile> expectedEntry : expectedFiles.entrySet()) {
                String path = expectedEntry.getKey();
                ExpectedFile expected = expectedEntry.getValue();
                EntryScan actual = actualEntries.get(path);
    
                if (actual == null) {
                    errors.add("Missing package file: " + path);
                    filesValid = false;
                    continue;
                }
    
                verifiedFileBytes += actual.size();
    
                if (expected.checksum() == null || expected.checksum().isBlank()) {
                    errors.add("Missing SHA-256 checksum in manifest for: " + path);
                    filesValid = false;
                } else if (!expected.checksum().equalsIgnoreCase(actual.sha256())) {
                    errors.add("Checksum mismatch for: " + path);
                    filesValid = false;
                }
    
                if (expected.sizeBytes() != null
                        && expected.sizeBytes() >= 0
                        && expected.sizeBytes() != actual.size()) {
                    errors.add("Size mismatch for: " + path);
                    filesValid = false;
                }
    
                String expectedExtension = expected.extension();
                if (expectedExtension == null) {
                    expectedExtension = extensionFromPath(path);
                }
    
                if (expectedExtension == null) {
                    errors.add("Missing file extension for content inspection: " + path);
                    filesValid = false;
                } else {
                    try {
                        FileContentInspector.validatePackageEntry(
                                actual.prefix(),
                                expectedExtension,
                                actual.zipSpool()
                        );
                    } catch (Exception e) {
                        errors.add("Content type mismatch for " + path + ": " + e.getMessage());
                        filesValid = false;
                    }
                }
            }
    
            Set<String> expectedEntryNames = new HashSet<>(expectedFiles.keySet());
            for (String actualName : actualEntries.keySet()) {
                if (!isManifestEntry(actualName) && !expectedEntryNames.contains(actualName)) {
                    warnings.add("Unexpected package entry: " + actualName);
                }
            }
    
            int projectsCount = manifest.path("projects").isArray() ? manifest.path("projects").size() : 0;
            int filesCount = expectedFiles.size();
    
            return new Verification(
                    manifestHashValid,
                    schemaVersionValid,
                    filesValid,
                    schemaVersion,
                    projectsCount,
                    filesCount,
                    verifiedFileBytes,
                    errors,
                    warnings,
                    manifest
            );
            } finally {
            cleanupEntrySpools(actualEntries, warnings);
        }
    }

    public void withEntry(InputStream packageInput,
                          String requestedEntryName,
                          EntryConsumer consumer) throws Exception {
        String normalizedRequested = normalizeEntryName(requestedEntryName);

        try (ZipInputStream zip = new ZipInputStream(packageInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                String entryName = normalizeEntryName(entry.getName());
                if (entryName.equals(normalizedRequested)) {
                    consumer.accept(zip, entry.getSize());
                    zip.closeEntry();
                    return;
                }

                drain(zip);
                zip.closeEntry();
            }
        }

        throw new IllegalArgumentException("Package entry not found: " + normalizedRequested);
    }

    public String resolveFileEntryPath(JsonNode fileNode) {
        String objectKey = textOrNull(fileNode, "object_key");
        if (objectKey != null) {
            return normalizeEntryName(objectKey);
        }

        String localPath = textOrNull(fileNode, "local_path");
        if (localPath != null) {
            return normalizeEntryName(localPath);
        }

        String projectId = fileNode.path("project_id").asText("");
        String originalName = fileNode.path("original_name").asText(
                fileNode.path("name").asText("file"));

        if (projectId.isBlank()) {
            throw new IllegalArgumentException("Cannot resolve package entry path without project_id");
        }
        return normalizeEntryName(projectId + "/" + originalName);
    }

    public String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    public String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(data));
    }

    public boolean isSchemaSupported(String schemaVersion) {
        return schemaVersion != null && supportedSchemaVersions.contains(schemaVersion);
    }

    private byte[] readManifestBytes(InputStream packageInput) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(packageInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && isManifestEntry(normalizeEntryName(entry.getName()))) {
                    return readLimited(zip, MAX_MANIFEST_BYTES);
                }
                drain(zip);
                zip.closeEntry();
            }
        }
        throw new IllegalArgumentException("manifest.json was not found in the package");
    }

    private Map<String, ExpectedFile> expectedFiles(JsonNode manifest, List<String> errors) {
        Map<String, ExpectedFile> expected = new HashMap<>();
        JsonNode projects = manifest.path("projects");
        if (!projects.isArray()) {
            errors.add("Manifest projects must be an array");
            return expected;
        }

        for (JsonNode project : projects) {
            JsonNode files = project.path("files");
            if (!files.isArray()) {
                continue;
            }

            for (JsonNode file : files) {
                try {
                    String path = resolveFileEntryPath(file);
                    String checksum = textOrNull(file, "checksum_sha256");
                    Long size = file.hasNonNull("size_bytes") ? file.path("size_bytes").asLong() : null;
                    String extension = textOrNull(file, "extension");
                    if (extension == null) {
                        extension = extensionFromPath(
                                file.path("original_name").asText(file.path("name").asText(path))
                        );
                    }
                    if (expected.put(path, new ExpectedFile(checksum, size, extension)) != null) {
                        errors.add("Duplicate file path in manifest: " + path);
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
        }
        return expected;
    }

    private String textOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private boolean isManifestEntry(String entryName) {
        return MANIFEST_FILE_NAME.equals(entryName)
                || entryName.endsWith("/" + MANIFEST_FILE_NAME);
    }

    private String normalizeEntryName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Package entry path is missing");
        }

        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Unsafe package entry path: " + value);
            }
        }
        return normalized;
    }

    private byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException("manifest.json exceeds the maximum allowed size");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (input.read(buffer) != -1) {
            // consume current ZIP entry
        }
    }

    private String toHex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @FunctionalInterface
    public interface EntryConsumer {
        void accept(InputStream entryStream, long declaredSize) throws Exception;
    }

    public record Inspection(
            String packageHash,
            String manifestHash,
            String manifestJson,
            JsonNode manifest,
            long totalSizeBytes
    ) {}

    public record Verification(
            boolean manifestHashValid,
            boolean schemaVersionValid,
            boolean filesValid,
            String schemaVersion,
            int projectsCount,
            int filesCount,
            long verifiedFileBytes,
            List<String> errors,
            List<String> warnings,
            JsonNode manifest
    ) {}

    private void cleanupEntrySpools(Map<String, EntryScan> actualEntries,
                                    List<String> warnings) {
        for (EntryScan scan : actualEntries.values()) {
            if (scan.zipSpool() == null) {
                continue;
            }
            try {
                Files.deleteIfExists(scan.zipSpool());
            } catch (IOException cleanupError) {
                warnings.add("Unable to remove temporary inspection file");
            }
        }
    }

    private byte[] firstBytes(byte[] data, int maxBytes) {
        int length = Math.min(data.length, maxBytes);
        byte[] prefix = new byte[length];
        System.arraycopy(data, 0, prefix, 0, length);
        return prefix;
    }

    private String extensionFromPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        int dot = value.lastIndexOf('.');
        if (dot <= slash || dot == value.length() - 1) {
            return null;
        }
        return value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record ExpectedFile(String checksum, Long sizeBytes, String extension) {}
    private record EntryScan(String sha256, long size, byte[] prefix, Path zipSpool) {}
}
