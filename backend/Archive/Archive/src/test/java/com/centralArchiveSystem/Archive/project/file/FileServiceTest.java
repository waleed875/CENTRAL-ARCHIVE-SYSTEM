package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FileServiceTest {

    @Test
    void directMetadataAccessRejectsFileAboveUserClearance() {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID fileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID projectLevelId = UUID.randomUUID();
        UUID fileLevelId = UUID.randomUUID();

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);
        file.setStatus("ACTIVE");
        file.setConfidentialityLevelId(fileLevelId);

        Project project = new Project();
        project.setId(projectId);
        project.setConfidentialityLevelId(projectLevelId);

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        doNothing().when(confidentiality).requireReadAccess(projectLevelId);
        doThrow(new AccessDeniedException("denied"))
                .when(confidentiality).requireReadAccess(fileLevelId);

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality
        );

        assertThrows(AccessDeniedException.class, () -> service.getFileMetadata(fileId));
        verify(confidentiality).requireReadAccess(projectLevelId);
        verify(confidentiality).requireReadAccess(fileLevelId);
        verifyNoInteractions(minioService);
    }

    @Test
    void directDownloadRejectsWhenProjectIsAboveUserClearance() {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID fileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID projectLevelId = UUID.randomUUID();

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);
        file.setStatus("ACTIVE");
        file.setBucketName("archive-files");
        file.setObjectKey("projects/test/file.pdf");

        Project project = new Project();
        project.setId(projectId);
        project.setConfidentialityLevelId(projectLevelId);

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        doThrow(new AccessDeniedException("denied"))
                .when(confidentiality).requireReadAccess(projectLevelId);

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality
        );

        assertThrows(AccessDeniedException.class, () -> service.downloadFile(fileId));
        verifyNoInteractions(minioService);
    }


    @Test
    void rejectsFileMutationForApprovedProject() {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID fileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.APPROVED);

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality
        );

        assertThrows(IllegalStateException.class,
                () -> service.softDelete(fileId, UUID.randomUUID(), "test"));
        verify(fileRepository, never()).save(any());
        verifyNoInteractions(minioService);
    }

    @Test
    void uploadWithoutFileTypeUsesFilenameExtensionForContentInspection() throws Exception {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID projectId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.DRAFT);

        com.centralArchiveSystem.Archive.project.filecategory.FileCategory category =
                new com.centralArchiveSystem.Archive.project.filecategory.FileCategory();
        category.setId(categoryId);
        category.setCode("DOCS");
        category.setName("Documents");
        category.setIsActive(true);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(minioConfig.getBucket()).thenReturn("archive-files");
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(invocation -> {
            FileEntity saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        MockMultipartFile multipart = new MockMultipartFile(
                "file",
                "report.pdf",
                "application/octet-stream",
                "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)
        );

        com.centralArchiveSystem.Archive.project.file.dto.FileUploadRequest request =
                new com.centralArchiveSystem.Archive.project.file.dto.FileUploadRequest(
                        categoryId,
                        null,
                        null,
                        null,
                        null
                );

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality
        );

        FileResponse response = service.uploadFile(
                projectId,
                multipart,
                request,
                UUID.randomUUID()
        );

        assertEquals(null, response.typeId());
        assertEquals("application/pdf", response.mimeType());
        verifyNoInteractions(typeRepository);
    }

    @Test
    void metadataUpdateRejectsCategoryTypeMismatch() {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID fileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID originalCategoryId = UUID.randomUUID();
        UUID newCategoryId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.DRAFT);

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);
        file.setCategoryId(originalCategoryId);
        file.setTypeId(typeId);

        com.centralArchiveSystem.Archive.project.filecategory.FileCategory newCategory =
                new com.centralArchiveSystem.Archive.project.filecategory.FileCategory();
        newCategory.setId(newCategoryId);
        newCategory.setCode("NEW");
        newCategory.setName("New");
        newCategory.setIsActive(true);

        com.centralArchiveSystem.Archive.project.filetype.FileType type =
                new com.centralArchiveSystem.Archive.project.filetype.FileType();
        type.setId(typeId);
        type.setCategoryId(originalCategoryId);
        type.setCode("TXT");
        type.setName("Text");
        type.setIsActive(true);

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(categoryRepository.findById(newCategoryId)).thenReturn(Optional.of(newCategory));
        when(typeRepository.findById(typeId)).thenReturn(Optional.of(type));

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateFileMetadata(
                        fileId,
                        new com.centralArchiveSystem.Archive.project.file.dto.FileMetadataUpdateRequest(
                                newCategoryId,
                                null,
                                null,
                                null,
                                null
                        )
                )
        );

        verify(fileRepository, never()).save(any());
    }

    @Test
    void manualUploadDefaultsSourceTypeWhenMetadataOmitsIt() throws Exception {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID projectId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.DRAFT);

        com.centralArchiveSystem.Archive.project.filecategory.FileCategory category =
                new com.centralArchiveSystem.Archive.project.filecategory.FileCategory();
        category.setId(categoryId);
        category.setCode("DOCS");
        category.setName("Documents");
        category.setIsActive(true);

        com.centralArchiveSystem.Archive.project.filetype.FileType type =
                new com.centralArchiveSystem.Archive.project.filetype.FileType();
        type.setId(typeId);
        type.setCategoryId(categoryId);
        type.setCode("TXT");
        type.setName("Text");
        type.setDefaultExtension("txt");
        type.setIsActive(true);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(typeRepository.findById(typeId)).thenReturn(Optional.of(type));
        when(minioConfig.getBucket()).thenReturn("archive-files");
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(invocation -> {
            FileEntity saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        MockMultipartFile multipart = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello archive".getBytes(StandardCharsets.UTF_8)
        );

        com.centralArchiveSystem.Archive.project.file.dto.FileUploadRequest request =
                new com.centralArchiveSystem.Archive.project.file.dto.FileUploadRequest(
                        categoryId,
                        typeId,
                        null,
                        null,
                        null
                );

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality
        );

        service.uploadFile(projectId, multipart, request, UUID.randomUUID());

        var captor = org.mockito.ArgumentCaptor.forClass(FileEntity.class);
        verify(fileRepository).save(captor.capture());
        assertEquals("MANUAL", captor.getValue().getSourceType());
    }

    @Test
    void softDeletedFileCannotBeReadByDirectId() {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID fileId = UUID.randomUUID();

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(UUID.randomUUID());
        file.setStatus("DELETED");
        file.setDeletedAt(java.time.LocalDateTime.now());

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality
        );

        assertThrows(EntityNotFoundException.class, () -> service.getFileMetadata(fileId));
        verifyNoInteractions(projectRepository, confidentiality, minioService);
    }
}
