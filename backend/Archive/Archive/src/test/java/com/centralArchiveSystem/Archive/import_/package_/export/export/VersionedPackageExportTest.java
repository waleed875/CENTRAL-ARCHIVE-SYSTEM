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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VersionedPackageExportTest {

    @Test
    void selectedHistoricalVersionExportsEntireFamilyWithFilesAndValidV2Manifest() throws Exception {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        FileRepository files = mock(FileRepository.class);
        MinioService minio = mock(MinioService.class);
        ProjectExportRepository exports = mock(ProjectExportRepository.class);
        ConfidentialityAccessService clearance = mock(ConfidentialityAccessService.class);
        MinioConfig config = new MinioConfig();
        config.setBucket("test-bucket");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PackageArchiveService archive = new PackageArchiveService(mapper, "1.0,2.0");

        UUID familyUid = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        Project first = project(UUID.randomUUID(), familyUid, org, 1, null, false);
        Project second = project(UUID.randomUUID(), familyUid, org, 2, first.getId(), true);
        byte[] b1 = "v1-content".getBytes(StandardCharsets.UTF_8);
        byte[] b2 = "v2-content".getBytes(StandardCharsets.UTF_8);
        FileEntity file1 = file(first.getId(), b1, "a.txt");
        FileEntity file2 = file(second.getId(), b2, "b.txt");

        when(projects.findById(first.getId())).thenReturn(Optional.of(first));
        when(projects.findById(second.getId())).thenReturn(Optional.of(second));
        when(projects.findByProjectUidOrderByVersionNoAsc(familyUid))
                .thenReturn(List.of(first, second));
        when(members.findByProjectId(any())).thenReturn(List.of());
        when(files.findByProjectIdAndDeletedAtIsNull(first.getId())).thenReturn(List.of(file1));
        when(files.findByProjectIdAndDeletedAtIsNull(second.getId())).thenReturn(List.of(file2));
        when(files.findById(file1.getId())).thenReturn(Optional.of(file1));
        when(files.findById(file2.getId())).thenReturn(Optional.of(file2));
        when(minio.downloadFile("test-bucket", file1.getObjectKey()))
                .thenAnswer(ignored -> new ByteArrayInputStream(b1));
        when(minio.downloadFile("test-bucket", file2.getObjectKey()))
                .thenAnswer(ignored -> new ByteArrayInputStream(b2));
        when(exports.save(any(ProjectExport.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<byte[]> packageBytes = new AtomicReference<>();
        doAnswer(inv -> { packageBytes.set(((InputStream)inv.getArgument(0)).readAllBytes()); return null; })
                .when(minio).uploadRaw(any(InputStream.class), eq("test-bucket"), anyString(),
                        eq("application/octet-stream"), anyLong());

        ExportService service = new ExportService(projects, members, files, minio, config, exports,
                mapper, archive, clearance, UUID.randomUUID());
        ExportRequest request = new ExportRequest(List.of(second.getId(), first.getId()),
                "ALL", new ExportRequest.ExportContents(true, true),
                "PKG", new ExportRequest.ExportProtection(true, "SHA256"));

        ExportPreviewResponse preview = service.preview(request);
        assertEquals(2, preview.selectedProjects());
        assertEquals(1, preview.projectFamilies());
        assertEquals(2, preview.versions());
        assertEquals(2, preview.files());

        ProjectExport result = service.createExport(request, UUID.randomUUID(), "export-test");
        assertEquals("2.0", result.getSchemaVersion());
        assertEquals("EXPORTED", result.getStatus());
        assertEquals(2, result.getProjectsCount());
        assertEquals(2, result.getFilesCount());
        JsonNode manifest = mapper.readTree(result.getManifestData());
        assertEquals(2, manifest.path("projects").size());
        JsonNode firstVersion = manifest.path("projects").get(0);
        JsonNode secondVersion = manifest.path("projects").get(1);
        assertEquals(familyUid.toString(), firstVersion.path("project_uid").asText());
        assertEquals(1, firstVersion.path("version_no").asInt());
        assertFalse(firstVersion.path("is_latest_version").asBoolean());
        assertEquals(first.getId().toString(), secondVersion.path("previous_project_id").asText());
        assertEquals(2, secondVersion.path("version_no").asInt());
        assertTrue(secondVersion.path("is_latest_version").asBoolean());
        assertNotNull(packageBytes.get());

        PackageArchiveService.Verification verified = archive.verifyContents(
                new ByteArrayInputStream(packageBytes.get()), result.getManifestHash(), "2.0");
        assertTrue(verified.errors().isEmpty(), verified.errors().toString());
        assertTrue(verified.manifestHashValid());
        assertTrue(verified.filesValid());
        assertTrue(verified.schemaVersionValid());
        assertEquals(2, verified.filesCount());
        verify(clearance, atLeastOnce()).requireReadAccess(first.getConfidentialityLevelId());
        verify(clearance, atLeastOnce()).requireReadAccess(second.getConfidentialityLevelId());
    }

    @Test
    void rejectedSelectedProjectCannotBePreviewedForExport() {
        ProjectRepository projects = mock(ProjectRepository.class);
        Project rejected = project(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, null, true);
        rejected.setStatus(Project.ProjectStatus.REJECTED);
        when(projects.findById(rejected.getId())).thenReturn(Optional.of(rejected));
        ConfidentialityAccessService clearance = mock(ConfidentialityAccessService.class);

        ExportService service = new ExportService(projects, mock(ProjectMemberRepository.class),
                mock(FileRepository.class), mock(MinioService.class), new MinioConfig(),
                mock(ProjectExportRepository.class), new ObjectMapper(),
                new PackageArchiveService(new ObjectMapper(), "1.0,2.0"), clearance, UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> service.preview(new ExportRequest(List.of(rejected.getId()))));
        assertThrows(IllegalArgumentException.class, () -> service.preview(new ExportRequest(
                List.of(rejected.getId()), "ALL",
                new ExportRequest.ExportContents(true, true), "PKG",
                new ExportRequest.ExportProtection(true, "SHA256"))));
        verify(projects, never()).findByProjectUidOrderByVersionNoAsc(any());
    }

    @Test
    void refusesIncompleteVersionHistoryInsteadOfExportingSilentPartialPackage() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ConfidentialityAccessService clearance = mock(ConfidentialityAccessService.class);
        Project first = project(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, null, false);
        when(projects.findById(first.getId())).thenReturn(Optional.of(first));
        when(projects.findByProjectUidOrderByVersionNoAsc(first.getProjectUid()))
                .thenReturn(List.of(first)); // missing latest version
        ExportService service = new ExportService(projects, mock(ProjectMemberRepository.class),
                mock(FileRepository.class), mock(MinioService.class), new MinioConfig(),
                mock(ProjectExportRepository.class), new ObjectMapper(),
                new PackageArchiveService(new ObjectMapper(), "1.0,2.0"), clearance, UUID.randomUUID());
        ExportRequest request = new ExportRequest(List.of(first.getId()), "ALL",
                new ExportRequest.ExportContents(true, true), "PKG",
                new ExportRequest.ExportProtection(true, "SHA256"));
        assertThrows(IllegalStateException.class, () -> service.preview(request));
    }

    private static Project project(UUID id, UUID uid, UUID org, int version,
                                   UUID previous, boolean latest) {
        Project p = new Project();
        p.setId(id); p.setProjectUid(uid); p.setOrganizationId(org);
        p.setVersionNo(version); p.setPreviousProjectId(previous); p.setIsLatestVersion(latest);
        p.setProjectNumber("PRJ-VERSION-123");
        p.setName("Historical Project"); p.setYear(2026);
        p.setStatus(Project.ProjectStatus.APPROVED);
        p.setConfidentialityLevelId(UUID.randomUUID());
        return p;
    }

    private static FileEntity file(UUID projectId, byte[] bytes, String name) throws Exception {
        FileEntity f = new FileEntity();
        f.setId(UUID.randomUUID()); f.setProjectId(projectId); f.setCategoryId(UUID.randomUUID());
        f.setConfidentialityLevelId(UUID.randomUUID());
        f.setOriginalName(name); f.setExtension("txt"); f.setMimeType("text/plain");
        f.setBucketName("test-bucket"); f.setObjectKey("files/" + f.getId() + "/" + name);
        f.setSizeBytes((long)bytes.length);
        f.setChecksumSha256(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        f.setStatus("ACTIVE");
        return f;
    }
}
