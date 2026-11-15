package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationService;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectTypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackageDefaultFileCategoryTest {
    @Test
    void requiresAnActiveDefaultCategoryWhenPackageFileHasNoCategoryId() {
        UUID packageId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        FileCategoryRepository categories = mock(FileCategoryRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        BranchPackage pkg = new BranchPackage();
        pkg.setId(packageId);
        pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
        pkg.setSchemaVersion("1.0");
        pkg.setManifestData("""
            {"projects":[{
               "project_id":"%s",
               "organization_id":"%s",
               "project_number":"PKG-1",
               "name":"Document",
               "year":2026,
               "files":[{"original_name":"package.txt","size_bytes":0}]
            }]}
            """.formatted(UUID.randomUUID(), organizationId));

        Organization organization = new Organization();
        organization.setId(organizationId);
        organization.setIsActive(true);
        when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));
        when(packages.findById(packageId)).thenReturn(Optional.of(pkg));

        BranchPackageService service = new BranchPackageService(
                packages, mock(PackageProjectRepository.class), mapper, projects,
                mock(ProjectMemberRepository.class), mock(FileRepository.class),
                mock(MinioService.class), mock(MinioConfig.class),
                mock(PackageArchiveService.class), new SealedPackageInspector(mapper),
                new SealedPackageDecryptor(), new DecryptedPayloadValidator(mapper),
                new SealedPayloadCanonicalizer(mapper), mock(ImportOperationService.class),
                organizations, mock(ProjectTypeRepository.class), categories,
                mock(FileTypeRepository.class), mock(ConfidentialityAccessService.class),
                mock(PlatformTransactionManager.class));

        PackageImportRequest withoutDefault = new PackageImportRequest(Map.of(), null);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.assertImportable(packageId, withoutDefault));
        assertTrue(missing.getMessage().contains("default file category"));

        FileCategory category = new FileCategory();
        category.setId(categoryId);
        category.setIsActive(true);
        when(categories.findById(categoryId)).thenReturn(Optional.of(category));
        PackageImportRequest withDefault = new PackageImportRequest(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, null, categoryId);
        assertDoesNotThrow(() -> service.assertImportable(packageId, withDefault));

        category.setIsActive(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.assertImportable(packageId, withDefault));
    }
}
