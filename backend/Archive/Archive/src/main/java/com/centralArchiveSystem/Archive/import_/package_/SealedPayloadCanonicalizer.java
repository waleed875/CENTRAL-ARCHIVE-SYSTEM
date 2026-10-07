package com.centralArchiveSystem.Archive.import_.package_;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class SealedPayloadCanonicalizer {

    private static final String MANIFEST = "manifest.json";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_MANIFEST_BYTES = 10 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    @Value("${archive.packages.max-expanded-file-bytes:21474836480}")
    private long maxExpandedFileBytes = 20L * 1024 * 1024 * 1024;

    @Value("${archive.packages.max-expanded-total-bytes:42949672960}")
    private long maxExpandedTotalBytes = 40L * 1024 * 1024 * 1024;

    @Value("${archive.packages.max-archive-entries:100000}")
    private int maxArchiveEntries = 100_000;

    public SealedPayloadCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalPackage canonicalize(Path decryptedZip, JsonNode signedHeader) throws Exception {
        if (decryptedZip == null || !Files.isRegularFile(decryptedZip)) {
            throw new IllegalArgumentException("Decrypted payload ZIP is missing");
        }
        if (signedHeader == null || !signedHeader.isObject()) {
            throw new IllegalArgumentException("Signed package header is missing");
        }

        Scan scan = scan(decryptedZip);
        JsonNode innerManifest = objectMapper.readTree(scan.manifestBytes());
        if (innerManifest == null || !innerManifest.isObject()) {
            throw new IllegalArgumentException(
                    "Decrypted payload manifest.json must contain a JSON object");
        }

        ObjectNode canonicalManifest = buildCanonicalManifest(signedHeader, innerManifest, scan.entries());
        byte[] manifestBytes = objectMapper.writeValueAsBytes(canonicalManifest);
        String manifestHash = sha256(manifestBytes);

        Path canonicalPath = Files.createTempFile("central-archive-canonical-", ".pkg");
        boolean success = false;
        try {
            writeCanonicalArchive(decryptedZip, canonicalPath, manifestBytes);
            long canonicalSize = Files.size(canonicalPath);
            String canonicalHash;
            try (InputStream input = Files.newInputStream(canonicalPath)) {
                canonicalHash = sha256(input);
            }

            JsonNode projects = canonicalManifest.path("projects");
            int projectCount = projects.isArray() ? projects.size() : 0;
            int fileCount = 0;
            if (projects.isArray()) {
                for (JsonNode project : projects) {
                    if (project.path("files").isArray()) {
                        fileCount += project.path("files").size();
                    }
                }
            }

            success = true;
            return new CanonicalPackage(
                    canonicalPath,
                    objectMapper.writeValueAsString(canonicalManifest),
                    canonicalManifest,
                    manifestHash,
                    canonicalHash,
                    canonicalSize,
                    projectCount,
                    fileCount
            );
        } finally {
            if (!success) {
                Files.deleteIfExists(canonicalPath);
            }
        }
    }

    private Scan scan(Path decryptedZip) throws Exception {
        Map<String, EntryInfo> entries = new LinkedHashMap<>();
        byte[] manifestBytes = null;
        long totalExpanded = 0L;
        int entryCount = 0;

        try (InputStream input = Files.newInputStream(decryptedZip);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                entryCount++;
                if (entryCount > maxArchiveEntries) {
                    throw new IllegalArgumentException(
                            "Decrypted payload exceeds maximum archive entry count");
                }

                String name = normalizeEntryName(entry.getName());
                if (entries.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "Duplicate decrypted payload entry: " + name);
                }

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                ByteArrayOutputStream manifestOut = MANIFEST.equals(name)
                        ? new ByteArrayOutputStream()
                        : null;
                byte[] buffer = new byte[BUFFER_SIZE];
                long size = 0L;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read;
                    totalExpanded += read;
                    if (size > maxExpandedFileBytes) {
                        throw new IllegalArgumentException(
                                "Decrypted payload entry exceeds maximum expanded size: " + name);
                    }
                    if (totalExpanded > maxExpandedTotalBytes) {
                        throw new IllegalArgumentException(
                                "Decrypted payload exceeds maximum expanded size");
                    }
                    digest.update(buffer, 0, read);
                    if (manifestOut != null) {
                        if (manifestOut.size() + read > MAX_MANIFEST_BYTES) {
                            throw new IllegalArgumentException(
                                    "Decrypted manifest.json exceeds maximum allowed size");
                        }
                        manifestOut.write(buffer, 0, read);
                    }
                }

                entries.put(name, new EntryInfo(name, size, toHex(digest.digest())));
                if (manifestOut != null) {
                    manifestBytes = manifestOut.toByteArray();
                }
                zip.closeEntry();
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException(
                    "Decrypted payload is not a valid ZIP archive", e);
        }

        if (manifestBytes == null) {
            throw new IllegalArgumentException(
                    "Decrypted payload ZIP has no manifest.json at its root");
        }
        return new Scan(entries, manifestBytes);
    }

    private ObjectNode buildCanonicalManifest(JsonNode header,
                                              JsonNode innerManifest,
                                              Map<String, EntryInfo> entries) throws Exception {
        ObjectNode canonical = objectMapper.createObjectNode();
        copyRequired(canonical, header, "package_uuid");
        copyOptional(canonical, header, "package_name");
        copyRequired(canonical, header, "schema_version");
        copyRequired(canonical, header, "source_terminal_uuid");
        copyOptional(canonical, header, "source_organization_id");
        copyOptional(canonical, header, "source_username");

        JsonNode headerProjects = header.path("projects");
        if (!headerProjects.isArray()) {
            throw new IllegalArgumentException("Signed package header has no projects array");
        }

        List<InnerDocument> documents = innerDocuments(innerManifest, entries);
        Set<String> usedEntries = new LinkedHashSet<>();
        ArrayNode canonicalProjects = canonical.putArray("projects");

        for (JsonNode sourceProject : headerProjects) {
            if (!sourceProject.isObject()) {
                throw new IllegalArgumentException(
                        "Signed package project metadata must be objects");
            }
            ObjectNode project = ((ObjectNode) sourceProject).deepCopy();

            if (!project.hasNonNull("organization_id")) {
                if (project.hasNonNull("organization_entity_id")) {
                    project.set("organization_id", project.get("organization_entity_id"));
                } else if (header.hasNonNull("source_organization_id")) {
                    project.set("organization_id", header.get("source_organization_id"));
                }
            }

            JsonNode sourceFiles = sourceProject.path("files");
            ArrayNode canonicalFiles = objectMapper.createArrayNode();
            if (sourceFiles.isArray()) {
                for (JsonNode sourceFile : sourceFiles) {
                    if (!sourceFile.isObject()) {
                        throw new IllegalArgumentException(
                                "Signed package file metadata must be objects");
                    }
                    ObjectNode file = ((ObjectNode) sourceFile).deepCopy();
                    file.remove("content_base64");

                    EntryInfo resolved = resolveEntry(file, documents, entries, usedEntries);
                    verifyHeaderFileMetadata(file, resolved);
                    file.put("object_key", resolved.name());
                    file.put("size_bytes", resolved.size());
                    file.put("checksum_sha256", resolved.sha256());
                    canonicalFiles.add(file);
                    usedEntries.add(resolved.name());
                }
            }
            project.set("files", canonicalFiles);
            canonicalProjects.add(project);
        }

        if (canonicalProjects.size() == 0) {
            throw new IllegalArgumentException("Signed package contains no projects");
        }
        return canonical;
    }

    private EntryInfo resolveEntry(ObjectNode file,
                                   List<InnerDocument> documents,
                                   Map<String, EntryInfo> entries,
                                   Set<String> usedEntries) {
        String declaredPath = textOrNull(file, "object_key");
        if (declaredPath != null) {
            String normalized = normalizeEntryName(declaredPath);
            EntryInfo direct = entries.get(normalized);
            if (direct != null && !MANIFEST.equals(normalized) && !usedEntries.contains(normalized)) {
                return direct;
            }
        }

        String originalName = firstNonBlank(
                textOrNull(file, "original_name"),
                textOrNull(file, "name")
        );
        String checksum = lower(textOrNull(file, "checksum_sha256"));
        String extension = normalizeExtension(textOrNull(file, "extension"));
        Long size = file.hasNonNull("size_bytes") && file.path("size_bytes").canConvertToLong()
                ? file.path("size_bytes").asLong()
                : null;

        List<EntryInfo> documentMatches = new ArrayList<>();
        for (InnerDocument document : documents) {
            EntryInfo entry = entries.get(document.entryPath());
            if (entry == null || usedEntries.contains(entry.name())) {
                continue;
            }
            if (originalName != null
                    && document.originalName() != null
                    && !originalName.equals(document.originalName())) {
                continue;
            }
            if (checksum != null
                    && document.hash() != null
                    && !checksum.equalsIgnoreCase(document.hash())) {
                continue;
            }
            if (extension != null
                    && document.extension() != null
                    && !extension.equalsIgnoreCase(document.extension())) {
                continue;
            }
            if (matchesMetadata(entry, checksum, size, extension)) {
                documentMatches.add(entry);
            }
        }
        EntryInfo uniqueDocumentMatch = unique(documentMatches);
        if (uniqueDocumentMatch != null) {
            return uniqueDocumentMatch;
        }

        List<EntryInfo> hashMatches = new ArrayList<>();
        for (EntryInfo entry : entries.values()) {
            if (MANIFEST.equals(entry.name()) || usedEntries.contains(entry.name())) {
                continue;
            }
            if (matchesMetadata(entry, checksum, size, extension)) {
                hashMatches.add(entry);
            }
        }
        EntryInfo uniqueHashMatch = unique(hashMatches);
        if (uniqueHashMatch != null) {
            return uniqueHashMatch;
        }

        if (originalName != null) {
            List<EntryInfo> nameMatches = new ArrayList<>();
            for (EntryInfo entry : entries.values()) {
                if (MANIFEST.equals(entry.name()) || usedEntries.contains(entry.name())) {
                    continue;
                }
                String basename = entry.name().substring(entry.name().lastIndexOf('/') + 1);
                if (originalName.equals(basename)
                        && matchesMetadata(entry, checksum, size, extension)) {
                    nameMatches.add(entry);
                }
            }
            EntryInfo uniqueNameMatch = unique(nameMatches);
            if (uniqueNameMatch != null) {
                return uniqueNameMatch;
            }
        }

        throw new IllegalArgumentException(
                "Unable to resolve decrypted payload entry for file: "
                        + (originalName != null ? originalName : "(unnamed file)"));
    }

    private boolean matchesMetadata(EntryInfo entry,
                                    String checksum,
                                    Long size,
                                    String extension) {
        if (checksum != null && !checksum.equalsIgnoreCase(entry.sha256())) {
            return false;
        }
        if (size != null && size >= 0 && size.longValue() != entry.size()) {
            return false;
        }
        if (extension != null) {
            String entryExtension = extensionFromPath(entry.name());
            if (entryExtension != null && !extension.equalsIgnoreCase(entryExtension)) {
                return false;
            }
        }
        return checksum != null || size != null || extension != null;
    }

    private void verifyHeaderFileMetadata(ObjectNode file, EntryInfo resolved) {
        String checksum = textOrNull(file, "checksum_sha256");
        if (checksum != null && !checksum.equalsIgnoreCase(resolved.sha256())) {
            throw new IllegalArgumentException(
                    "Decrypted file checksum does not match signed header: " + resolved.name());
        }
        if (file.hasNonNull("size_bytes") && file.path("size_bytes").canConvertToLong()) {
            long expected = file.path("size_bytes").asLong();
            if (expected >= 0 && expected != resolved.size()) {
                throw new IllegalArgumentException(
                        "Decrypted file size does not match signed header: " + resolved.name());
            }
        }
    }

    private List<InnerDocument> innerDocuments(JsonNode innerManifest,
                                                Map<String, EntryInfo> entries) {
        JsonNode docs = innerManifest.path("documents");
        if (!docs.isArray()) {
            return List.of();
        }

        List<InnerDocument> result = new ArrayList<>();
        for (JsonNode doc : docs) {
            if (!doc.isObject()) {
                continue;
            }
            String originalName = firstNonBlank(
                    textOrNull(doc, "originalName"),
                    textOrNull(doc, "original_name"),
                    textOrNull(doc, "name")
            );
            String hash = firstNonBlank(
                    textOrNull(doc, "hash"),
                    textOrNull(doc, "checksum_sha256")
            );
            String extension = normalizeExtension(textOrNull(doc, "extension"));
            String entryPath = firstNonBlank(
                    textOrNull(doc, "objectKey"),
                    textOrNull(doc, "object_key"),
                    textOrNull(doc, "localPath"),
                    textOrNull(doc, "local_path")
            );

            if (entryPath == null) {
                String fileId = firstNonBlank(
                        textOrNull(doc, "fileId"),
                        textOrNull(doc, "file_id")
                );
                if (fileId != null) {
                    String suffix = extension == null ? "" : "." + extension;
                    String candidate = "files/" + fileId + suffix;
                    if (entries.containsKey(candidate)) {
                        entryPath = candidate;
                    } else {
                        String rawExtension = textOrNull(doc, "extension");
                        candidate = "files/" + fileId + (rawExtension == null ? "" : rawExtension);
                        if (entries.containsKey(candidate)) {
                            entryPath = candidate;
                        }
                    }
                }
            }

            if (entryPath != null) {
                String normalized = normalizeEntryName(entryPath);
                if (entries.containsKey(normalized) && !MANIFEST.equals(normalized)) {
                    result.add(new InnerDocument(
                            normalized,
                            originalName,
                            hash,
                            extension
                    ));
                }
            }
        }
        return result;
    }

    private void writeCanonicalArchive(Path decryptedZip,
                                       Path canonicalZip,
                                       byte[] canonicalManifest) throws Exception {
        try (OutputStream fileOutput = Files.newOutputStream(canonicalZip);
             ZipOutputStream output = new ZipOutputStream(fileOutput)) {

            ZipEntry manifestEntry = new ZipEntry(MANIFEST);
            output.putNextEntry(manifestEntry);
            output.write(canonicalManifest);
            output.closeEntry();

            try (InputStream input = Files.newInputStream(decryptedZip);
                 ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                byte[] buffer = new byte[BUFFER_SIZE];
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zip.closeEntry();
                        continue;
                    }
                    String name = normalizeEntryName(entry.getName());
                    if (MANIFEST.equals(name)) {
                        drain(zip);
                        zip.closeEntry();
                        continue;
                    }

                    output.putNextEntry(new ZipEntry(name));
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    output.closeEntry();
                    zip.closeEntry();
                }
            }
        }
    }

    private void copyRequired(ObjectNode target, JsonNode source, String field) {
        if (!source.hasNonNull(field) || source.path(field).asText("").isBlank()) {
            throw new IllegalArgumentException("Missing required signed header field: " + field);
        }
        target.set(field, source.get(field));
    }

    private void copyOptional(ObjectNode target, JsonNode source, String field) {
        if (source.hasNonNull(field)) {
            target.set(field, source.get(field));
        }
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

    private String extensionFromPath(String value) {
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        if (dot <= slash || dot == value.length() - 1) {
            return null;
        }
        return value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeExtension(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private EntryInfo unique(List<EntryInfo> candidates) {
        Map<String, EntryInfo> unique = new LinkedHashMap<>();
        for (EntryInfo candidate : candidates) {
            unique.put(candidate.name(), candidate);
        }
        return unique.size() == 1 ? unique.values().iterator().next() : null;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private void drain(InputStream input) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (input.read(buffer) != -1) {
            // consume
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record Scan(Map<String, EntryInfo> entries, byte[] manifestBytes) {}
    private record EntryInfo(String name, long size, String sha256) {}
    private record InnerDocument(String entryPath, String originalName, String hash, String extension) {}

    public static final class CanonicalPackage implements AutoCloseable {
        private final Path path;
        private final String manifestJson;
        private final JsonNode manifest;
        private final String manifestHash;
        private final String packageHash;
        private final long sizeBytes;
        private final int projectsCount;
        private final int filesCount;

        private CanonicalPackage(Path path,
                                 String manifestJson,
                                 JsonNode manifest,
                                 String manifestHash,
                                 String packageHash,
                                 long sizeBytes,
                                 int projectsCount,
                                 int filesCount) {
            this.path = path;
            this.manifestJson = manifestJson;
            this.manifest = manifest;
            this.manifestHash = manifestHash;
            this.packageHash = packageHash;
            this.sizeBytes = sizeBytes;
            this.projectsCount = projectsCount;
            this.filesCount = filesCount;
        }

        public Path path() { return path; }
        public String manifestJson() { return manifestJson; }
        public JsonNode manifest() { return manifest; }
        public String manifestHash() { return manifestHash; }
        public String packageHash() { return packageHash; }
        public long sizeBytes() { return sizeBytes; }
        public int projectsCount() { return projectsCount; }
        public int filesCount() { return filesCount; }

        @Override
        public void close() throws Exception {
            Files.deleteIfExists(path);
        }
    }
}
