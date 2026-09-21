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
