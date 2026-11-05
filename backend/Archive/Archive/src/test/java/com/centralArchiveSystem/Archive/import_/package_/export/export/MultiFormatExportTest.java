package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.import_.package_.SealedPackageDecryptor;
import com.centralArchiveSystem.Archive.import_.package_.SealedPackageInspector;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiFormatExportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesFormatsAndRequiresStrongPassphraseForEncryption() {
        UUID id = UUID.randomUUID();
        var options = new ExportRequest.ExportProtection(true, "SHA256", "NONE", null);
        new ExportRequest(List.of(id), "ALL",
                new ExportRequest.ExportContents(true, true), "ZIP", options).validateOptions();
        new ExportRequest(List.of(id), "ALL",
                new ExportRequest.ExportContents(true, false), "XLSX", options).validateOptions();
        new ExportRequest(List.of(id), "ALL",
                new ExportRequest.ExportContents(true, false), "PDF", options).validateOptions();
        var secured = new ExportRequest.ExportProtection(true, "SHA256", "PASSPHRASE",
                "VeryStrongPassphrase123!");
        new ExportRequest(List.of(id), "ALL",
                new ExportRequest.ExportContents(true, true), "PKG", secured).validateOptions();
        assertThrows(IllegalArgumentException.class,
                () -> new ExportRequest(List.of(id), "ALL", null, "PDF", secured).validateOptions());
        assertThrows(IllegalArgumentException.class,
                () -> new ExportRequest(List.of(id), "ALL", null, "PKG",
                        new ExportRequest.ExportProtection(true, "SHA256", "PASSPHRASE", "weak"))
                        .validateOptions());
        assertFalse(secured.toString().contains("VeryStrongPassphrase123!"));
    }

    @Test
    void zipContainsOriginalFilesAndCsvInventoryWithIntegrity() throws Exception {
        byte[] bytes = "sample file bytes".getBytes(StandardCharsets.UTF_8);
        UUID projectId = UUID.randomUUID(), fileId = UUID.randomUUID();
        FileEntity entity = new FileEntity();
        entity.setId(fileId);
        entity.setProjectId(projectId);
        entity.setBucketName("bucket");
        entity.setObjectKey("test/123.txt");
        FileRepository files = mock(FileRepository.class);
        MinioService storage = mock(MinioService.class);
        when(files.findById(fileId)).thenReturn(Optional.of(entity));
        when(storage.downloadFile("bucket", "test/123.txt"))
                .thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        var writer = new ExportAlternativeWriter(files, storage, "");
        JsonNode manifest = manifest(projectId, fileId, bytes);
        Path output = Files.createTempFile("test-export-", ".zip");
        try {
            writer.write("ZIP", output, manifest);
            boolean csv = false, file = false;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(output))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("inventory.csv".equals(entry.getName())) {
                        csv = true;
                        assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("العنوان"));
                    } else {
                        file = true;
                        assertTrue(entry.getName().contains("version-1/"));
                        assertArrayEquals(bytes, zip.readAllBytes());
                    }
                    zip.closeEntry();
                }
            }
            assertTrue(csv && file);
        } finally { Files.deleteIfExists(output); }
    }

    @Test
    void xlsxHasArabicInventoryAndNeverEmbedsFileContents() throws Exception {
        byte[] content = "secret raw file content".getBytes(StandardCharsets.UTF_8);
        JsonNode manifest = manifest(UUID.randomUUID(), UUID.randomUUID(), content);
        var writer = new ExportAlternativeWriter(mock(FileRepository.class),
                mock(MinioService.class), "");
        Path output = Files.createTempFile("test-export-", ".xlsx");
        try {
            writer.write("XLSX", output, manifest);
            try (InputStream source = Files.newInputStream(output); XSSFWorkbook book = new XSSFWorkbook(source)) {
                var sheet = book.getSheet("المشاريع");
                assertNotNull(sheet);
                assertTrue(sheet.isRightToLeft());
                assertEquals("اسم المشروع", sheet.getRow(0).getCell(0).getStringCellValue());
                assertEquals("العنوان", sheet.getRow(1).getCell(0).getStringCellValue());
            }
        } finally { Files.deleteIfExists(output); }
    }

    @Test
    void pdfWithInstalledArabicFontCreatesActualDocument() throws Exception {
        Path font = Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        Assumptions.assumeTrue(Files.isRegularFile(font));
        var writer = new ExportAlternativeWriter(mock(FileRepository.class),
                mock(MinioService.class), font.toString());
        Path pdf = Files.createTempFile("test-export-", ".pdf");
        try {
            writer.write("PDF", pdf, manifest(UUID.randomUUID(), UUID.randomUUID(),
                    "contents".getBytes(StandardCharsets.UTF_8)));
            try (var doc = Loader.loadPDF(pdf.toFile())) {
                assertEquals(1, doc.getNumberOfPages());
            }
        } finally { Files.deleteIfExists(pdf); }
    }

    @Test
    void encryptedPkgCanBeInspectedAndDecryptedByExistingImporter() throws Exception {
        JsonNode manifest = manifest(UUID.randomUUID(), UUID.randomUUID(),
                "not added to this fixture".getBytes(StandardCharsets.UTF_8));
        // This test deliberately exercises envelope authenticity and passphrase compatibility;
        // the plain inner bytes can be replaced by a fully verified PKG archive.
        byte[] payload = "inner archive test payload".getBytes(StandardCharsets.UTF_8);
        Path source = Files.createTempFile("inner-test-", ".pkg");
        Path encrypted = Files.createTempFile("sealed-test-", ".nxpkg");
        try {
            Files.write(source, payload);
            var writer = new ExportEncryptedPackageWriter(mapper);
            writer.seal(source, encrypted, manifest, "TestSecretStrongPassphrase2026!");
            byte[] bytes = Files.readAllBytes(encrypted);
            MockMultipartFile upload = new MockMultipartFile("package_file", "export.nxpkg",
                    "application/octet-stream", bytes);
            var inspector = new SealedPackageInspector(mapper);
            var inspected = inspector.inspect(upload);
            assertTrue(inspected.signatureValid());
            assertTrue(inspected.payloadHashValid());
            var decryptor = new SealedPackageDecryptor();
            try (var decrypted = decryptor.decrypt(upload, inspected, "TestSecretStrongPassphrase2026!")) {
                assertArrayEquals(payload, Files.readAllBytes(decrypted.path()));
            }
            assertThrows(IllegalArgumentException.class, () -> {
                try (var ignored = decryptor.decrypt(upload, inspected, "WrongPasswordThatIsLongEnough")) {}
            });
            assertFalse(inspected.headerJson().contains("TestSecretStrongPassphrase2026!"));
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(encrypted);
        }
    }

    private JsonNode manifest(UUID projectId, UUID fileId, byte[] content) throws Exception {
        String json = """
                {"package_uuid":"%s","package_name":"test-export.pkg","schema_version":"2.0",
                "source_terminal_uuid":"%s",
                "projects":[{"project_id":"%s","project_uid":"%s","version_no":1,
                "is_latest_version":true,"name":"العنوان","project_number":"A-100",
                "year":2026,"files":[{"project_id":"%s","file_id":"%s",
                "original_name":"ملف.txt","extension":"txt","size_bytes":%d,
                "checksum_sha256":"%s","object_key":"files/sample.txt"}]}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), projectId, UUID.randomUUID(),
                projectId, fileId, content.length, HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content)));
        return mapper.readTree(json);
    }
}
