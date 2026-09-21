package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationService;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchPackageServiceTest {

    @Test
    void importOperationFailsIfPackageIsNoLongerVerified() {
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        PackageProjectRepository packageProjects = mock(PackageProjectRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        FileRepository files = mock(FileRepository.class);
        MinioService minio = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        PackageArchiveService archive = mock(PackageArchiveService.class);
        ImportOperationService operations = mock(ImportOperationService.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        UUID packageId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID importedBy = UUID.randomUUID();

        BranchPackage pkg = new BranchPackage();
        pkg.setId(packageId);
        pkg.setPackageUuid(UUID.randomUUID());
        pkg.setStatus(BranchPackage.PackageStatus.FAILED);

        ImportOperation operation = new ImportOperation();
        operation.setId(operationId);
        operation.setPackageId(packageId);
        operation.setImportedBy(importedBy);
        operation.setStatus(ImportOperation.Status.STARTED);

        when(operations.getById(operationId)).thenReturn(operation);
        when(packages.findById(packageId)).thenReturn(Optional.of(pkg));

        BranchPackageService service = new BranchPackageService(
                packages,
                packageProjects,
                objectMapper,
                projects,
                members,
                files,
                minio,
                minioConfig,
                archive,
                operations,
                organizations,
                confidentiality,
                transactionManager
        );

        service.importPackage(
                packageId,
                new PackageImportRequest(
                        Map.of(),
                        null
                ),
                importedBy,
                operationId
        );

        verify(operations).updateStatus(
                eq(operationId),
                eq(ImportOperation.Status.FAILED),
                contains("must remain VERIFIED")
        );
        verifyNoInteractions(projects);
    }

    @Test
    void fileFailureProducesPartialOperationAndIncompleteImportedProject() throws Exception {
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        PackageProjectRepository packageProjects = mock(PackageProjectRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        FileRepository files = mock(FileRepository.class);
        MinioService minio = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        PackageArchiveService archive = mock(PackageArchiveService.class);
        ImportOperationService operations = mock(ImportOperationService.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        UUID packageId = UUID.randomUUID();
        UUID packageUuid = UUID.randomUUID();
        UUID targetOrganizationId = UUID.randomUUID();
        UUID sourceOrganizationId = UUID.randomUUID();
        UUID sourceProjectId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID importedBy = UUID.randomUUID();

        String manifest = """
                {
                  "projects": [{
                    "project_id": "%s",
                    "organization_id": "%s",
                    "project_number": "P-001",
                    "name": "Partial Import Project",
                    "year": 2026,
                    "files": [{
                      "original_name": "document.txt",
                      "extension": "txt",
                      "object_key": "files/document.txt",
                      "size_bytes": 10,
                      "checksum_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    }]
                  }]
                }
                """.formatted(sourceProjectId, sourceOrganizationId);

        BranchPackage pkg = new BranchPackage();
        pkg.setId(packageId);
        pkg.setPackageUuid(packageUuid);
        pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
        pkg.setManifestData(manifest);

        ImportOperation operation = new ImportOperation();
        operation.setId(operationId);
        operation.setPackageId(packageId);
        operation.setImportedBy(importedBy);
        operation.setStatus(ImportOperation.Status.STARTED);

        when(packages.findById(packageId)).thenReturn(Optional.of(pkg));
        when(operations.getById(operationId)).thenReturn(operation);
        when(projects.findActiveByProjectNumberAndOrganizationIdAndYear(
                "P-001",
                targetOrganizationId,
                2026
        )).thenReturn(Optional.empty());

        when(projects.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            if (project.getId() == null) {
                project.setId(UUID.randomUUID());
            }
            return project;
        });

        when(minioConfig.getBucket()).thenReturn("archive-files");
        when(minio.downloadFile(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(archive.resolveFileEntryPath(any())).thenReturn("files/document.txt");
        doThrow(new IllegalStateException("simulated package entry failure"))
                .when(archive)
                .withEntry(any(), eq("files/document.txt"), any());

        BranchPackageService service = new BranchPackageService(
                packages,
                packageProjects,
                objectMapper,
                projects,
                members,
                files,
                minio,
                minioConfig,
                archive,
                operations,
                organizations,
                confidentiality,
                transactionManager
        );

        service.importPackage(
                packageId,
                new PackageImportRequest(
                        Map.of(sourceOrganizationId, targetOrganizationId),
                        null
                ),
                importedBy,
                operationId
        );

        assertEquals(BranchPackage.PackageStatus.IMPORTED, pkg.getStatus());
        assertNotNull(pkg.getImportedAt());
        assertNotNull(pkg.getErrorMessage());
        assertTrue(pkg.getErrorMessage().contains("1 project(s) imported incompletely"));

        verify(operations, never()).create(any(), any());
        verify(operations).updateStatus(
                eq(operationId),
                eq(ImportOperation.Status.PARTIAL),
                contains("1 project(s) imported incompletely")
        );

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeast(2)).save(projectCaptor.capture());
        Project lastSavedProject = projectCaptor.getAllValues()
                .get(projectCaptor.getAllValues().size() - 1);
        assertEquals(Project.ProjectStatus.IMPORTED, lastSavedProject.getStatus());
        assertEquals(Project.DataCompletionStatus.INCOMPLETE,
                lastSavedProject.getDataCompletionStatus());

        ArgumentCaptor<PackageProject> linkCaptor =
                ArgumentCaptor.forClass(PackageProject.class);
        verify(packageProjects).save(linkCaptor.capture());
        PackageProject link = linkCaptor.getValue();

        assertEquals(PackageProject.ImportStatus.IMPORTED, link.getImportStatus());
        assertEquals(1, link.getFilesCount());
        assertEquals(0, link.getFilesImported());
        assertEquals(1, link.getFilesFailed());
        assertNotNull(link.getImportError());

        verify(files, never()).save(any());
    }
    @Test
    void assertImportableRejectsMissingOrganizationMapping() {
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        PackageProjectRepository packageProjects = mock(PackageProjectRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        FileRepository files = mock(FileRepository.class);
        MinioService minio = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        PackageArchiveService archive = mock(PackageArchiveService.class);
        ImportOperationService operations = mock(ImportOperationService.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        UUID packageId = UUID.randomUUID();
        UUID sourceOrganizationId = UUID.randomUUID();

        BranchPackage pkg = new BranchPackage();
        pkg.setId(packageId);
        pkg.setPackageUuid(UUID.randomUUID());
        pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
        pkg.setManifestData("""
                {
                  "projects": [{
                    "project_id": "%s",
                    "organization_id": "%s",
                    "project_number": "P-MAP",
                    "name": "Mapping Project",
                    "year": 2026,
                    "files": []
                  }]
                }
                """.formatted(UUID.randomUUID(), sourceOrganizationId));

        when(packages.findById(packageId)).thenReturn(Optional.of(pkg));

        BranchPackageService service = new BranchPackageService(
                packages,
                packageProjects,
                objectMapper,
                projects,
                members,
                files,
                minio,
                minioConfig,
                archive,
                operations,
                organizations,
                confidentiality,
                transactionManager
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.assertImportable(
                        packageId,
                        new PackageImportRequest(Map.of(), null)
                )
        );

        assertTrue(error.getMessage().contains("Missing organization mapping"));
        verifyNoInteractions(organizations);
        verifyNoInteractions(confidentiality);
    }

    @Test
    void projectImportFailureRollsBackItsOwnTransaction() {
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        PackageProjectRepository packageProjects = mock(PackageProjectRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        FileRepository files = mock(FileRepository.class);
        MinioService minio = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        PackageArchiveService archive = mock(PackageArchiveService.class);
        ImportOperationService operations = mock(ImportOperationService.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        UUID packageId = UUID.randomUUID();
        UUID packageUuid = UUID.randomUUID();
        UUID targetOrganizationId = UUID.randomUUID();
        UUID sourceOrganizationId = UUID.randomUUID();
        UUID sourceProjectId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID importedBy = UUID.randomUUID();

        String manifest = """
                {
                  "projects": [{
                    "project_id": "%s",
                    "organization_id": "%s",
                    "project_number": "P-ROLLBACK",
                    "name": "Rollback Project",
                    "year": 2026,
                    "members": [{
                      "person_name": "Member"
                    }],
                    "files": []
                  }]
                }
                """.formatted(sourceProjectId, sourceOrganizationId);

        BranchPackage pkg = new BranchPackage();
        pkg.setId(packageId);
        pkg.setPackageUuid(packageUuid);
        pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
        pkg.setManifestData(manifest);

        ImportOperation operation = new ImportOperation();
        operation.setId(operationId);
        operation.setPackageId(packageId);
        operation.setImportedBy(importedBy);
        operation.setStatus(ImportOperation.Status.STARTED);

        when(packages.findById(packageId)).thenReturn(Optional.of(pkg));
        when(operations.getById(operationId)).thenReturn(operation);
        when(projects.findActiveByProjectNumberAndOrganizationIdAndYear(
                "P-ROLLBACK", targetOrganizationId, 2026
        )).thenReturn(Optional.empty());
        when(projects.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(UUID.randomUUID());
            return project;
        });
        doThrow(new IllegalStateException("member persistence failed"))
                .when(members).save(any());

        BranchPackageService service = new BranchPackageService(
                packages,
                packageProjects,
                objectMapper,
                projects,
                members,
                files,
                minio,
                minioConfig,
                archive,
                operations,
                organizations,
                confidentiality,
                transactionManager
        );

        service.importPackage(
                packageId,
                new PackageImportRequest(
                        Map.of(sourceOrganizationId, targetOrganizationId),
                        null
                ),
                importedBy,
                operationId
        );

        verify(transactionManager).rollback(transactionStatus);
        verify(packageProjects, never()).save(any());
        verify(operations).updateStatus(
                eq(operationId),
                eq(ImportOperation.Status.FAILED),
                contains("All projects failed")
        );
    }

}
