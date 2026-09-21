package com.centralArchiveSystem.Archive.project.file;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class FileContentInspector {

    public static final int SNIFF_BYTES = 8192;

    private static final Set<String> ZIP_EXTENSIONS = Set.of(
            "zip", "docx", "xlsx", "pptx", "odt", "ods", "odp"
    );
    private static final Set<String> OLE_EXTENSIONS = Set.of(
            "doc", "xls", "ppt", "msg"
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "csv", "md", "json", "xml", "html", "htm", "svg", "dxf", "eml"
    );

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("webp", "image/webp"),
            Map.entry("zip", "application/zip"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
            Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
            Map.entry("doc", "application/msword"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("msg", "application/vnd.ms-outlook"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("md", "text/markdown"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("dxf", "application/dxf"),
            Map.entry("eml", "message/rfc822"),
            Map.entry("rtf", "application/rtf"),
            Map.entry("dwg", "application/acad"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime")
    );

    private FileContentInspector() {
    }

    public static String validateMultipart(MultipartFile file, String expectedExtension) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        String expected = normalizeExtension(expectedExtension);
        if (expected == null) {
            throw new IllegalArgumentException("Selected file type has no default extension");
        }

        String actual = extensionFromName(file.getOriginalFilename());
        if (!extensionsEquivalent(actual, expected)) {
            throw new IllegalArgumentException(
                    "File extension does not match selected file type; expected ." + expected);
        }

        try (InputStream input = file.getInputStream();
             PushbackInputStream pushback = new PushbackInputStream(input, SNIFF_BYTES)) {
            String mimeType = validateAndPreserve(pushback, expected);
            if (ZIP_EXTENSIONS.contains(expected) && !"zip".equals(expected)) {
                Path temp = Files.createTempFile("central-archive-content-", ".zip");
                try (InputStream fullInput = file.getInputStream()) {
                    Files.copy(fullInput, temp, StandardCopyOption.REPLACE_EXISTING);
                    validateZipContainer(temp, expected);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            return mimeType;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to inspect uploaded file content", e);
        }
    }

    public static String validateAndPreserve(PushbackInputStream input, String expectedExtension) throws IOException {
        String expected = normalizeExtension(expectedExtension);
        if (expected == null) {
            throw new IllegalArgumentException("File extension is required for content inspection");
        }

        byte[] prefix = input.readNBytes(SNIFF_BYTES);
        if (prefix.length == 0) {
            throw new IllegalArgumentException("File content is empty");
        }
        input.unread(prefix);

        return validatePrefix(prefix, expected);
    }

    private static String validatePrefix(byte[] prefix, String expected) {
        rejectExecutableContent(prefix);

        boolean valid = switch (expected) {
            case "pdf" -> startsWith(prefix, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "png" -> startsWith(prefix, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg", "jpeg" -> startsWith(prefix, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "gif" -> startsWith(prefix, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(prefix, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            case "tif", "tiff" -> startsWith(prefix, new byte[]{0x49, 0x49, 0x2A, 0x00})
                    || startsWith(prefix, new byte[]{0x4D, 0x4D, 0x00, 0x2A});
            case "bmp" -> startsWith(prefix, new byte[]{0x42, 0x4D});
            case "webp" -> prefix.length >= 12
                    && startsWith(prefix, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && matchesAt(prefix, 8, "WEBP".getBytes(StandardCharsets.US_ASCII));
            case "rtf" -> startsWithIgnoringBomAndWhitespace(prefix, "{\\rtf");
            case "dwg" -> startsWith(prefix, "AC10".getBytes(StandardCharsets.US_ASCII));
            case "mp4", "mov" -> prefix.length >= 12
                    && matchesAt(prefix, 4, "ftyp".getBytes(StandardCharsets.US_ASCII));
            default -> {
                if (ZIP_EXTENSIONS.contains(expected)) {
                    yield isZip(prefix);
                }
                if (OLE_EXTENSIONS.contains(expected)) {
                    yield startsWith(prefix, new byte[]{
                            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
                    });
                }
                if (TEXT_EXTENSIONS.contains(expected)) {
                    yield validateTextLike(prefix, expected);
                }
                throw new IllegalArgumentException(
                        "No content inspector is registered for file type extension: ." + expected);
            }
        };

        if (!valid) {
            throw new IllegalArgumentException(
                    "File content does not match the selected file type (." + expected + ")");
        }

        return MIME_TYPES.getOrDefault(expected, "application/octet-stream");
    }

    public static boolean hasZipSignature(byte[] prefix) {
        return prefix != null && isZip(prefix);
    }

    public static String validatePackageEntry(byte[] prefix,
                                              String expectedExtension,
                                              Path zipSpool) throws IOException {
        String expected = normalizeExtension(expectedExtension);
        if (expected == null) {
            throw new IllegalArgumentException("File extension is required for content inspection");
        }
        if (prefix == null || prefix.length == 0) {
            throw new IllegalArgumentException("File content is empty");
        }

        String mimeType = validatePrefix(prefix, expected);

        if (ZIP_EXTENSIONS.contains(expected) && !"zip".equals(expected)) {
            if (zipSpool == null) {
                throw new IllegalArgumentException(
                        "ZIP container is required for ." + expected + " inspection");
            }
            validateZipContainer(zipSpool, expected);
        }

        return mimeType;
    }

    private static void validateZipContainer(Path zipPath, String expected) throws IOException {
        boolean contentTypes = false;
        boolean expectedRoot = false;
        boolean openDocumentManifest = false;
        String openDocumentMime = null;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int entryCount = 0;

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > 2048) {
                    throw new IllegalArgumentException("Archive contains too many entries");
                }

                String name = entry.getName();
                if ("[Content_Types].xml".equals(name)) {
                    contentTypes = true;
                }
                if (name.startsWith("word/")) {
                    expectedRoot |= "docx".equals(expected);
                }
                if (name.startsWith("xl/")) {
                    expectedRoot |= "xlsx".equals(expected);
                }
                if (name.startsWith("ppt/")) {
                    expectedRoot |= "pptx".equals(expected);
                }
                if ("META-INF/manifest.xml".equals(name)) {
                    openDocumentManifest = true;
                }
                if ("mimetype".equals(name) && !entry.isDirectory()) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] mimeBytes = input.readNBytes(256);
                        openDocumentMime = new String(mimeBytes, StandardCharsets.US_ASCII).trim();
                    }
                }
            }
        }

        if (Set.of("docx", "xlsx", "pptx").contains(expected)) {
            if (!contentTypes || !expectedRoot) {
                throw new IllegalArgumentException(
                        "ZIP container does not match expected Office document type (." + expected + ")");
            }
            return;
        }

        String expectedMime = switch (expected) {
            case "odt" -> "application/vnd.oasis.opendocument.text";
            case "ods" -> "application/vnd.oasis.opendocument.spreadsheet";
            case "odp" -> "application/vnd.oasis.opendocument.presentation";
            default -> null;
        };

        if (expectedMime != null
                && (!openDocumentManifest || !expectedMime.equals(openDocumentMime))) {
            throw new IllegalArgumentException(
                    "ZIP container does not match expected OpenDocument type (." + expected + ")");
        }
    }

    public static boolean extensionsEquivalent(String first, String second) {
        String a = normalizeExtension(first);
        String b = normalizeExtension(second);
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return (Set.of("jpg", "jpeg").contains(a) && Set.of("jpg", "jpeg").contains(b))
                || (Set.of("tif", "tiff").contains(a) && Set.of("tif", "tiff").contains(b))
                || (Set.of("htm", "html").contains(a) && Set.of("htm", "html").contains(b));
    }

    public static String extensionFromName(String fileName) {
        if (fileName == null) {
            return null;
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return null;
        }
        return normalizeExtension(fileName.substring(lastDot + 1));
    }

    private static String normalizeExtension(String extension) {
        if (extension == null) {
            return null;
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static void rejectExecutableContent(byte[] prefix) {
        boolean windowsExecutable = startsWith(prefix, new byte[]{0x4D, 0x5A});
        boolean elfExecutable = startsWith(prefix, new byte[]{0x7F, 0x45, 0x4C, 0x46});
        if (windowsExecutable || elfExecutable) {
            throw new IllegalArgumentException("Executable file content is not allowed");
        }
    }

    private static boolean isZip(byte[] prefix) {
        return startsWith(prefix, new byte[]{0x50, 0x4B, 0x03, 0x04})
                || startsWith(prefix, new byte[]{0x50, 0x4B, 0x05, 0x06})
                || startsWith(prefix, new byte[]{0x50, 0x4B, 0x07, 0x08});
    }

    private static boolean validateTextLike(byte[] prefix, String extension) {
        if (!isValidUtf8Text(prefix)) {
            return false;
        }

        String text = new String(prefix, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .stripLeading();

        return switch (extension) {
            case "json" -> text.startsWith("{") || text.startsWith("[");
            case "xml", "svg", "html", "htm" -> text.startsWith("<");
            case "dxf" -> text.startsWith("0") || text.startsWith("999");
            default -> true;
        };
    }

    private static boolean isValidUtf8Text(byte[] prefix) {
        for (byte value : prefix) {
            if (value == 0) {
                return false;
            }
        }

        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(prefix));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean startsWithIgnoringBomAndWhitespace(byte[] prefix, String value) {
        if (!isValidUtf8Text(prefix)) {
            return false;
        }
        String text = new String(prefix, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .stripLeading();
        return text.startsWith(value);
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        return matchesAt(data, 0, signature);
    }

    private static boolean matchesAt(byte[] data, int offset, byte[] signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
