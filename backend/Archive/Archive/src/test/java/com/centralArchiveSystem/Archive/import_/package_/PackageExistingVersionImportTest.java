package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationService;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectTypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackageExistingVersionImportTest {
    private final BranchPackageRepository packages = mock(BranchPackageRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final ConfidentialityAccessService clearance = mock(ConfidentialityAccessService.class);
    private final UUID packageId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final BranchPackage pkg = new BranchPackage();
    private final Project target = new Project();
    private BranchPackageService service;

    @BeforeEach
    void setup() {
        ObjectMapper mapper = new ObjectMapper();
        service = new BranchPackageService(
                packages, mock(PackageProjectRepository.class), mapper, projects,
                mock(ProjectMemberRepository.class), mock(FileRepository.class),
                mock(MinioService.class), mock(MinioConfig.class),
                mock(PackageArchiveService.class), new SealedPackageInspector(mapper),
                new SealedPackageDecryptor(), new DecryptedPayloadValidator(mapper),
                new SealedPayloadCanonicalizer(mapper),
                mock(ImportOperationService.class), organizations,
                mock(ProjectTypeRepository.class), mock(FileCategoryRepository.class),
                mock(FileTypeRepository.class), clearance,
                mock(PlatformTransactionManager.class));
        pkg.setId(packageId);
        pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
        pkg.setSchemaVersion("1.0");
        pkg.setManifestData(manifest(1, "P-1", 2026));
        when(packages.findById(packageId)).thenReturn(Optional.of(pkg));

        Organization organization = new Organization();
        organization.setId(organizationId);
        organization.setIsActive(true);
        when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));
        target.setId(targetId);
        target.setOrganizationId(organizationId);
        target.setProjectNumber("P-1");
        target.setYear(2026);
        target.setStatus(Project.ProjectStatus.APPROVED);
        target.setIsLatestVersion(true);
        when(projects.findById(targetId)).thenReturn(Optional.of(target));
    }

    private String manifest(int count, String number, int year) {
        String project = """
            {"project_id":"%s","organization_id":"%s",
             "project_number":"%s","name":"sample","year":%d,"files":[]}
            """.formatted(UUID.randomUUID(), organizationId, number, year);
        return "{\"projects\":[" + String.join(",", java.util.Collections.nCopies(count, project)) + "]}";
    }

    private PackageImportRequest request() {
        return new PackageImportRequest(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), null, targetId);
    }

    @Test
    void acceptsMatchingLatestApprovedTarget() {
        assertDoesNotThrow(() -> service.assertImportable(packageId, request()));
        verify(projects).findById(targetId);
    }

    @Test
    void rejectsMismatchedProjectNumber() {
        pkg.setManifestData(manifest(1, "DIFFERENT", 2026));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assertImportable(packageId, request()));
        assertTrue(error.getMessage().contains("must match"));
    }

    @Test
    void rejectsNonLatestOrUnapprovedTarget() {
        target.setIsLatestVersion(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.assertImportable(packageId, request()));
        target.setIsLatestVersion(true);
        target.setStatus(Project.ProjectStatus.DRAFT);
        assertThrows(IllegalArgumentException.class,
                () -> service.assertImportable(packageId, request()));
    }

    @Test
    void refusesMultipleProjectsAndUnsupportedSchema() {
        pkg.setManifestData(manifest(2, "P-1", 2026));
        assertThrows(IllegalArgumentException.class,
                () -> service.assertImportable(packageId, request()));
        pkg.setManifestData(manifest(1, "P-1", 2026));
        pkg.setSchemaVersion("2.0");
        assertThrows(IllegalArgumentException.class,
                () -> service.assertImportable(packageId, request()));
    }
}
