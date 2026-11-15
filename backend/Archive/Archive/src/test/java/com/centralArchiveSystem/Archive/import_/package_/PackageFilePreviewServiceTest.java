package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PackageFilePreviewServiceTest {
    private final UUID id = UUID.randomUUID();
    private final UUID uuid = UUID.randomUUID();
    private final BranchPackageRepository packages = mock(BranchPackageRepository.class);
    private final MinioConfig minioConfig = mock(MinioConfig.class);
    private final MinioService minio = mock(MinioService.class);
    private final PackageArchiveService archive = mock(PackageArchiveService.class);
    private final BranchPackage pkg = new BranchPackage();
    private PackageFilePreviewService service;

    @BeforeEach
    void setup() {
        pkg.setId(id);
        pkg.setPackageUuid(uuid);
        pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
        pkg.setManifestHash("verified-manifest");
        when(packages.findById(id)).thenReturn(Optional.of(pkg));
        when(minioConfig.getBucket()).thenReturn("archive");
        service = new PackageFilePreviewService(
                packages, minio, minioConfig, archive, new ObjectMapper());
    }

    @Test
    void previewsOriginalTextBytesWithoutImportingToProjectDatabase() throws Exception {
        byte[] content = "محتوى الملف داخل الحزمة".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(content);
        pkg.setManifestData(manifest("notes.md", content.length, checksum));
        when(archive.resolveFileEntryPath(any())).thenReturn("files/notes.md");
        // Provide the expected payload without creating a FileEntity.
        doAnswer(call -> {
            PackageArchiveService.EntryConsumer consumer = call.getArgument(2);
            consumer.accept(new ByteArrayInputStream(content), (long)content.length);
            return null;
        }).when(archive).withEntry(any(), eq("files/notes.md"), any());
        when(minio.downloadFile(eq("archive"), eq("packages/" + uuid + "/original.pkg")))
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        when(archive.sha256(content)).thenReturn(checksum);

        var file = service.read(id, 0, 0);
        assertEquals("notes.md", file.originalName());
        assertEquals("text/markdown", file.mimeType());
        assertArrayEquals(content, file.bytes());
        verify(packages).findById(id);
        verifyNoMoreInteractions(packages);
    }

    @Test
    void refusesTamperedAndOversizedEntries() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        pkg.setManifestData(manifest("notes.txt", content.length, "a".repeat(64)));
        when(archive.resolveFileEntryPath(any())).thenReturn("files/notes.txt");
        when(minio.downloadFile(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        doAnswer(call -> {
            PackageArchiveService.EntryConsumer consumer = call.getArgument(2);
            consumer.accept(new ByteArrayInputStream(content), (long)content.length);
            return null;
        }).when(archive).withEntry(any(), anyString(), any());
        when(archive.sha256(content)).thenReturn(sha256(content));
        assertThrows(ResponseStatusException.class, () -> service.read(id, 0, 0));

        pkg.setManifestData(manifest("notes.md", 3_000_000L, sha256(content)));
        assertThrows(ResponseStatusException.class, () -> service.read(id, 0, 0));
    }

    @Test
    void previewDoesNotReadUnverifiedPackagesOrInvalidIndexes() {
        pkg.setManifestData(manifest("notes.txt", 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
        pkg.setStatus(BranchPackage.PackageStatus.RECEIVED);
        assertThrows(ResponseStatusException.class, () -> service.read(id, 0, 0));
        pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
        assertThrows(ResponseStatusException.class, () -> service.read(id, 10, 0));
        assertThrows(ResponseStatusException.class, () -> service.read(id, 0, 9));
        verifyNoInteractions(minio);
    }

    private String manifest(String name, long size, String sha) {
        return """
            {"projects":[{"project_id":"%s","name":"Project","files":[
              {"original_name":"%s","extension":"%s","object_key":"files/%s",
               "size_bytes":%d,"checksum_sha256":"%s"}
            ]}]}
            """.formatted(UUID.randomUUID(), name, name.substring(name.lastIndexOf('.')+1), name, size, sha);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
