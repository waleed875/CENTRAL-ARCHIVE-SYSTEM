package com.centralArchiveSystem.Archive.project.file;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileContentInspectorTest {

    @Test
    void acceptsPdfByContentAndExpectedExtension() {
        byte[] bytes = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report.pdf",
                "application/octet-stream",
                bytes
        );

        assertEquals("application/pdf",
                FileContentInspector.validateMultipart(file, "pdf"));
    }

    @Test
    void rejectsExecutableDisguisedAsPdf() {
        byte[] bytes = new byte[]{0x4D, 0x5A, 0x00, 0x00, 0x01};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report.pdf",
                "application/pdf",
                bytes
        );

        assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.validateMultipart(file, "pdf"));
    }

    @Test
    void rejectsExtensionMismatchBeforeStorage() {
        byte[] bytes = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
        };
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "image.png",
                "image/png",
                bytes
        );

        assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.validateMultipart(file, "pdf"));
    }

    @Test
    void acceptsOfficeZipContainerForDocx() throws Exception {
        byte[] bytes = zip(
                "[Content_Types].xml",
                "word/document.xml"
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "document.docx",
                "application/zip",
                bytes
        );

        assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                FileContentInspector.validateMultipart(file, "docx")
        );
    }

    @Test
    void rejectsOrdinaryZipDisguisedAsDocx() throws Exception {
        byte[] bytes = zip("random.txt");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "document.docx",
                "application/zip",
                bytes
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> FileContentInspector.validateMultipart(file, "docx")
        );
    }

    @Test
    void rejectsBinaryPayloadForTextExtension() {
        byte[] bytes = new byte[]{0x00, 0x01, 0x02, 0x03};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                bytes
        );

        assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.validateMultipart(file, "txt"));
    }

    @Test
    void acceptsSqlByContent() {
        byte[] bytes = "-- seed roles\nINSERT INTO core.roles (code) VALUES ('ADMIN');\n"
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "UpdateProject.sql",
                "application/octet-stream",
                bytes
        );

        assertEquals("application/sql",
                FileContentInspector.validateMultipart(file, "sql"));
    }

    @Test
    void acceptsUtf16SqlBecauseLegacyEditorWroteItThatWay() {
        // The real ARC2 dumps store UpdateProject.sql as UTF-16LE with a BOM, so a plain NUL-byte
        // rejection would refuse legitimate legacy content.
        String sql = "USE central_archive;\nINSERT INTO core.roles (code) VALUES ('ADMIN');\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFE});
        out.writeBytes(sql.getBytes(StandardCharsets.UTF_16LE));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "UpdateProject.sql",
                "application/octet-stream",
                out.toByteArray()
        );

        assertEquals("application/sql",
                FileContentInspector.validateMultipart(file, "sql"));
    }

    @Test
    void stillRejectsBinaryThatMerelyStartsWithUtf16Bom() {
        byte[] bytes = new byte[]{
                (byte) 0xFF, (byte) 0xFE, 0x01, (byte) 0xD8, 0x00, (byte) 0xC0
        };
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "application/octet-stream",
                bytes
        );

        assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.validateMultipart(file, "txt"));
    }

    @Test
    void rejectsBinaryPayloadForSqlExtension() {
        byte[] bytes = new byte[]{0x00, 0x01, 0x02, 0x03};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "UpdateProject.sql",
                "application/octet-stream",
                bytes
        );

        assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.validateMultipart(file, "sql"));
    }

    @Test
    void acceptsRar4ArchiveByContent() {
        byte[] bytes = new byte[]{
                0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
        };
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "archive.rar",
                "application/octet-stream",
                bytes
        );

        assertEquals("application/vnd.rar",
                FileContentInspector.validateMultipart(file, "rar"));
    }

    @Test
    void acceptsRar5ArchiveByContent() {
        byte[] bytes = new byte[]{
                0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00
        };
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "archive.rar",
                "application/octet-stream",
                bytes
        );

        assertEquals("application/vnd.rar",
                FileContentInspector.validateMultipart(file, "rar"));
    }

    @Test
    void rejectsZipDisguisedAsRar() throws Exception {
        byte[] bytes = zip("random.txt");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "archive.rar",
                "application/zip",
                bytes
        );

        assertThrows(IllegalArgumentException.class,
                () -> FileContentInspector.validateMultipart(file, "rar"));
    }


    @Test
    void acceptsAdditionalMediaAndArchiveSignatures() {
        byte[] tar = new byte[512];
        byte[] ustar = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ustar, 0, tar, 257, ustar.length);
        byte[] m4a = ftyp("M4A ");
        byte[] heic = ftyp("heic");
        byte[] avif = ftyp("avif");
        byte[] webm = ebml("webm");
        byte[] mkv = ebml("matroska");

        Object[][] samples = {
                {"mp3", new byte[]{'I','D','3',4,0,0,0,0,0,0}, "audio/mpeg"},
                {"wav", riff("WAVE"), "audio/wav"},
                {"flac", new byte[]{'f','L','a','C'}, "audio/flac"},
                {"m4a", m4a, "audio/mp4"},
                {"avi", riff("AVI "), "video/x-msvideo"},
                {"webm", webm, "video/webm"},
                {"mkv", mkv, "video/x-matroska"},
                {"heic", heic, "image/heic"},
                {"avif", avif, "image/avif"},
                {"7z", new byte[]{0x37,0x7A,(byte)0xBC,(byte)0xAF,0x27,0x1C}, "application/x-7z-compressed"},
                {"tar", tar, "application/x-tar"},
                {"gz", new byte[]{0x1F,(byte)0x8B,8,0,0,0,0,0,0,0}, "application/gzip"}
        };
        for (Object[] sample : samples) {
            String extension = (String) sample[0];
            byte[] bytes = (byte[]) sample[1];
            String mimeType = (String) sample[2];
            var input = new MockMultipartFile("file", "archive." + extension, "application/octet-stream", bytes);
            assertEquals(mimeType, FileContentInspector.validateMultipart(input, extension),
                    "Failed to recognize valid ." + extension);
        }
    }

    @Test
    void rejectsCrossFormatSpoofingAndExecutablesInNewMediaTypes() {
        byte[] windowsExecutable = {0x4D,0x5A,0,0,0,0};
        byte[] linuxExecutable = {0x7F,'E','L','F',0,0};
        for (String type : new String[]{"mp3", "wav", "mkv", "webm", "7z", "heic", "tar", "gz"}) {
            for (byte[] executable : new byte[][]{windowsExecutable, linuxExecutable}) {
                var input = new MockMultipartFile("file", "malware." + type, "application/octet-stream", executable);
                assertThrows(IllegalArgumentException.class,
                        () -> FileContentInspector.validateMultipart(input, type), type);
            }
        }
        assertThrows(IllegalArgumentException.class, () ->
                FileContentInspector.validateMultipart(new MockMultipartFile("file", "app.exe",
                        "application/octet-stream", windowsExecutable), "exe"));
        assertThrows(IllegalArgumentException.class, () ->
                FileContentInspector.validateMultipart(new MockMultipartFile("file", "image.avif",
                        "image/avif", ftyp("heic")), "avif"));
        assertThrows(IllegalArgumentException.class, () ->
                FileContentInspector.validateMultipart(new MockMultipartFile("file", "recording.webm",
                        "video/webm", ebml("matroska")), "webm"));
    }

    private static byte[] riff(String format) {
        byte[] bytes = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy(format.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }

    private static byte[] ftyp(String brand) {
        byte[] bytes = new byte[24];
        bytes[3] = 24;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }

    private static byte[] ebml(String docType) {
        byte[] bytes = new byte[32];
        bytes[0] = 0x1A; bytes[1] = 0x45; bytes[2] = (byte)0xDF; bytes[3] = (byte)0xA3;
        bytes[4] = 0x42; bytes[5] = (byte)0x82; bytes[6] = (byte)(0x80 | docType.length());
        System.arraycopy(docType.getBytes(StandardCharsets.US_ASCII), 0, bytes, 7, docType.length());
        return bytes;
    }

    private byte[] zip(String... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String entryName : entries) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    @Test
    void acceptsCommonEquivalentExtensions() {
        assertTrue(FileContentInspector.extensionsEquivalent("jpeg", "jpg"));
        assertTrue(FileContentInspector.extensionsEquivalent("tiff", "tif"));
        assertTrue(FileContentInspector.extensionsEquivalent("htm", "html"));
        assertFalse(FileContentInspector.extensionsEquivalent("pdf", "docx"));
    }
}
