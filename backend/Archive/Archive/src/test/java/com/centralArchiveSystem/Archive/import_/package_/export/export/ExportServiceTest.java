package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.package_.PackageArchiveService;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportServiceTest {

    @Test
    void exportedPackageCanBeVerifiedByImportArchiveService() throws Exception {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectMemberRepository memberRepository = mock(ProjectMemberRepository.class);
        FileRepository fileRepository = mock(FileRepository.class);
        MinioService minioService = mock(MinioService.class);
        ProjectExportRepository exportRepository = mock(ProjectExportRepository.class);
        ConfidentialityAccessService confidentialityAccessService = mock(ConfidentialityAccessService.class);

        ObjectMapper objectMapper = new ObjectMapper();
        PackageArchiveService packageArchiveService = new PackageArchiveService(objectMapper, "1.0");

        MinioConfig minioConfig = new MinioConfig();
        minioConfig.setBucket("test-bucket");

        UUID projectId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID projectConfidentialityId = UUID.randomUUID();
        UUID fileConfidentialityId = UUID.randomUUID();
        UUID exportedBy = UUID.randomUUID();

        Project project = new Project();
        project.setId(projectId);
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setOrganizationId(organizationId);
        project.setProjectNumber("PRJ-001");
        project.setName("Round Trip Project");
        project.setYear(2026);
        project.setConfidentialityLevelId(projectConfidentialityId);
        project.setStatus(Project.ProjectStatus.APPROVED);

        byte[] sourceContent = "round trip package content".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(sourceContent);

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);
        file.setCategoryId(categoryId);
        file.setTypeId(typeId);
        file.setConfidentialityLevelId(fileConfidentialityId);
        file.setOriginalName("document.txt");
        file.setStoredName("stored.txt");
        file.setBucketName("test-bucket");
        file.setObjectKey("projects/source/stored.txt");
        file.setExtension("txt");
        file.setMimeType("text/plain");
        file.setSizeBytes((long) sourceContent.length);
        file.setChecksumSha256(checksum);
        file.setStatus("ACTIVE");

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(memberRepository.findByProjectId(projectId)).thenReturn(List.of());
        when(fileRepository.findByProjectIdAndDeletedAtIsNull(projectId)).thenReturn(List.of(file));
        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(minioService.downloadFile("test-bucket", "projects/source/stored.txt"))
                .thenAnswer(invocation -> new ByteArrayInputStream(sourceContent));
        when(exportRepository.save(any(ProjectExport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AtomicReference<byte[]> uploadedPackage = new AtomicReference<>();
        doAnswer(invocation -> {
            InputStream input = invocation.getArgument(0);
            uploadedPackage.set(input.readAllBytes());
            return null;
        }).when(minioService).uploadRaw(
                any(InputStream.class),
                eq("test-bucket"),
                anyString(),
                eq("application/octet-stream"),
                anyLong()
        );

        ExportService service = new ExportService(
                projectRepository,
                memberRepository,
                fileRepository,
                minioService,
                minioConfig,
                exportRepository,
                objectMapper,
                packageArchiveService,
                confidentialityAccessService,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        );

        ProjectExport result = service.createExport(
                List.of(projectId),
                organizationId,
                exportedBy,
                "export-user"
        );

        assertEquals("EXPORTED", result.getStatus());
        assertNotNull(result.getPackageHash());
        assertNotNull(result.getManifestHash());
        assertNotNull(uploadedPackage.get());

        JsonNode manifest = objectMapper.readTree(result.getManifestData());
        JsonNode manifestFile = manifest.path("projects").get(0).path("files").get(0);
        assertFalse(manifestFile.has("content_base64"));
        assertEquals(fileId.toString(), manifestFile.path("file_id").asText());

        PackageArchiveService.Verification verification = packageArchiveService.verifyContents(
                new ByteArrayInputStream(uploadedPackage.get()),
                result.getManifestHash(),
                "1.0"
        );

        assertTrue(verification.manifestHashValid());
        assertTrue(verification.schemaVersionValid());
        assertTrue(verification.filesValid());
        assertTrue(verification.errors().isEmpty());
        assertEquals(1, verification.projectsCount());
        assertEquals(1, verification.filesCount());

        verify(confidentialityAccessService).requireReadAccess(project.getConfidentialityLevelId());
        verify(confidentialityAccessService).requireReadAccess(file.getConfidentialityLevelId());
    }

    @Test
    void exportMetadataAndManifestRespectConfidentiality() throws Exception {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectMemberRepository memberRepository = mock(ProjectMemberRepository.class);
        FileRepository fileRepository = mock(FileRepository.class);
        MinioService minioService = mock(MinioService.class);
        ProjectExportRepository exportRepository = mock(ProjectExportRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        ObjectMapper objectMapper = new ObjectMapper();
        PackageArchiveService archiveService = new PackageArchiveService(objectMapper, "1.0");

        MinioConfig minioConfig = new MinioConfig();
        minioConfig.setBucket("test-bucket");

        UUID visibleLevel = UUID.randomUUID();
        UUID hiddenLevel = UUID.randomUUID();

        ProjectExport visible = new ProjectExport();
        visible.setId(UUID.randomUUID());
        visible.setPackageUuid(UUID.randomUUID());
        visible.setPackageName("visible.pkg");
        visible.setSchemaVersion("1.0");
        visible.setProjectsCount(1);
        visible.setFilesCount(0);
        visible.setStatus("EXPORTED");
        visible.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(1));
        visible.setPackageObjectKey("exports/private/visible.pkg");
        visible.setManifestData("""
                {
                  "projects": [{
                    "confidentiality_level_id": "%s",
                    "files": []
                  }]
                }
                """.formatted(visibleLevel));

        ProjectExport hidden = new ProjectExport();
        hidden.setId(UUID.randomUUID());
        hidden.setPackageUuid(UUID.randomUUID());
        hidden.setPackageName("hidden.pkg");
        hidden.setSchemaVersion("1.0");
        hidden.setProjectsCount(1);
        hidden.setFilesCount(0);
        hidden.setStatus("EXPORTED");
        hidden.setCreatedAt(java.time.LocalDateTime.now());
        hidden.setPackageObjectKey("exports/private/hidden.pkg");
        hidden.setManifestData("""
                {
                  "projects": [{
                    "confidentiality_level_id": "%s",
                    "files": []
                  }]
                }
                """.formatted(hiddenLevel));

        when(exportRepository.findAll()).thenReturn(List.of(visible, hidden));
        when(exportRepository.findById(hidden.getId())).thenReturn(Optional.of(hidden));

        doNothing().when(confidentiality).requireReadAccess(visibleLevel);
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(confidentiality).requireReadAccess(hiddenLevel);

        ExportService service = new ExportService(
                projectRepository,
                memberRepository,
                fileRepository,
                minioService,
                minioConfig,
                exportRepository,
                objectMapper,
                archiveService,
                confidentiality,
                UUID.randomUUID()
        );

        var listed = service.list(null);
        assertEquals(1, listed.size());
        assertEquals(visible.getId(), listed.get(0).getId());

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.getById(hidden.getId())
        );

        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsString(visible));
        assertFalse(serialized.has("manifestData"));
        assertFalse(serialized.has("packageObjectKey"));
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)
        );
    }
}
