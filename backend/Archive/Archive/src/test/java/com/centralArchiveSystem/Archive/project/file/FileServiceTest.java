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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

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
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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
                confidentiality,
                transactionManager
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
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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
                confidentiality,
                transactionManager
        );

        assertThrows(AccessDeniedException.class, () -> service.downloadFile(fileId));
        verifyNoInteractions(minioService);
    }


    @Test
    void fileMutationRejectsFileAboveUserClearance() {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        UUID fileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID fileLevelId = UUID.randomUUID();

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);
        file.setStatus("ACTIVE");
        file.setConfidentialityLevelId(fileLevelId);

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.DRAFT);

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        doThrow(new AccessDeniedException("denied"))
                .when(confidentiality).requireReadAccess(fileLevelId);

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality,
                transactionManager
        );

        assertThrows(
                AccessDeniedException.class,
                () -> service.softDelete(fileId, UUID.randomUUID(), "restricted")
        );
        verify(fileRepository, never()).saveAndFlush(any());
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
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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
                confidentiality,
                transactionManager
        );

        assertThrows(IllegalStateException.class,
                () -> service.softDelete(fileId, UUID.randomUUID(), "test"));
        verify(fileRepository, never()).saveAndFlush(any());
        verifyNoInteractions(minioService);
    }

    @Test
    void rejectsFileChangesWhileProjectIsUnderReviewOrRejected() {
        for (Project.ProjectStatus status : java.util.List.of(
                Project.ProjectStatus.UNDER_REVIEW,
                Project.ProjectStatus.REJECTED)) {
            FileRepository fileRepository = mock(FileRepository.class);
            FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
            FileTypeRepository typeRepository = mock(FileTypeRepository.class);
            MinioService minioService = mock(MinioService.class);
            MinioConfig minioConfig = mock(MinioConfig.class);
            ProjectRepository projectRepository = mock(ProjectRepository.class);
            ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            TransactionStatus transactionStatus = mock(TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

            UUID fileId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();

            FileEntity file = new FileEntity();
            file.setId(fileId);
            file.setProjectId(projectId);

            Project project = new Project();
            project.setId(projectId);
            project.setStatus(status);

            when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            FileService service = new FileService(
                    fileRepository,
                    categoryRepository,
                    typeRepository,
                    minioService,
                    minioConfig,
                    projectRepository,
                    confidentiality,
                    transactionManager
            );

            assertThrows(IllegalStateException.class,
                    () -> service.softDelete(fileId, UUID.randomUUID(), "locked"));
            verify(fileRepository, never()).save(any(FileEntity.class));
            verifyNoInteractions(minioService);
        }
    }

    @Test
    void duplicateChecksumIsRejectedBeforeObjectStorageUpload() throws Exception {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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

        byte[] content = "duplicate content".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content)
        );

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(minioConfig.getBucket()).thenReturn("archive-files");
        when(fileRepository.existsByProjectIdAndChecksumSha256AndDeletedAtIsNull(
                projectId,
                checksum
        )).thenReturn(true);

        MockMultipartFile multipart = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                content
        );

        var request = new com.centralArchiveSystem.Archive.project.file.dto.FileUploadRequest(
                categoryId,
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
                confidentiality,
                transactionManager
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.uploadFile(projectId, multipart, request, UUID.randomUUID())
        );

        verifyNoInteractions(minioService);
        verify(fileRepository, never()).saveAndFlush(any());
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
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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
        when(fileRepository.saveAndFlush(any(FileEntity.class))).thenAnswer(invocation -> {
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
                        null
                );

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality,
                transactionManager
        );

        var response = service.uploadFile(
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
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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
                confidentiality,
                transactionManager
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateFileMetadata(
                        fileId,
                        new com.centralArchiveSystem.Archive.project.file.dto.FileMetadataUpdateRequest(
                                newCategoryId,
                                null,
                                null
                        )
                )
        );

        verify(fileRepository, never()).saveAndFlush(any());
    }

    @Test
    void manualUploadUsesServerControlledProvenance() throws Exception {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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
        when(fileRepository.saveAndFlush(any(FileEntity.class))).thenAnswer(invocation -> {
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
                        null
                );

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality,
                transactionManager
        );

        service.uploadFile(projectId, multipart, request, UUID.randomUUID());

        var captor = org.mockito.ArgumentCaptor.forClass(FileEntity.class);
        verify(fileRepository).saveAndFlush(captor.capture());
        assertEquals("MANUAL", captor.getValue().getSourceType());
        assertEquals(null, captor.getValue().getSourceReference());
    }

    @Test
    void batchUploadUsesIndependentTransactionsPerFile() throws Exception {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus firstStatus = mock(TransactionStatus.class);
        TransactionStatus secondStatus = mock(TransactionStatus.class);

        when(transactionManager.getTransaction(any()))
                .thenReturn(firstStatus, secondStatus);

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

        when(fileRepository.saveAndFlush(any(FileEntity.class)))
                .thenAnswer(invocation -> {
                    FileEntity entity = invocation.getArgument(0);
                    if ("good.txt".equals(entity.getOriginalName())) {
                        entity.setId(UUID.randomUUID());
                        return entity;
                    }
                    throw new IllegalStateException("simulated database failure");
                });

        MockMultipartFile good = new MockMultipartFile(
                "files", "good.txt", "text/plain",
                "good content".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile bad = new MockMultipartFile(
                "files", "bad.txt", "text/plain",
                "bad content".getBytes(StandardCharsets.UTF_8));

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality,
                transactionManager
        );

        var result = service.uploadBatch(
                projectId,
                java.util.List.of(good, bad),
                new com.centralArchiveSystem.Archive.project.file.dto.FileUploadRequest(
                        categoryId, null, null),
                UUID.randomUUID()
        );

        assertEquals(1, result.totalSucceeded());
        assertEquals(1, result.totalFailed());
        verify(transactionManager).commit(firstStatus);
        verify(transactionManager).rollback(secondStatus);
    }

    @Test
    void importedFileWithoutCategoryOrTypeCanBeRead() {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        UUID fileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);
        file.setStatus("ACTIVE");
        file.setOriginalName("meeting_minutes.txt");
        file.setStoredName("stored.txt");
        file.setBucketName("archive-files");
        file.setObjectKey("import/package/project/stored.txt");
        file.setExtension("txt");
        file.setMimeType("text/plain");
        file.setSizeBytes(42L);
        file.setChecksumSha256("abc123");
        file.setSourceType("BRANCH_PACKAGE");
        file.setSourceReference(UUID.randomUUID().toString());

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.IMPORTED);

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality,
                transactionManager
        );

        var response = service.getFileMetadata(fileId);

        assertEquals(fileId, response.id());
        assertEquals(projectId, response.projectId());
        assertEquals("BRANCH_PACKAGE", response.sourceType());
        assertEquals("meeting_minutes.txt", response.originalName());
        assertEquals(null, response.categoryId());
        assertEquals(null, response.categoryCode());
        assertEquals(null, response.categoryName());
        assertEquals(null, response.typeId());
        assertEquals(null, response.typeCode());
        assertEquals(null, response.typeName());

        verifyNoInteractions(categoryRepository, typeRepository, minioService);
    }

    @Test
    void importedFileWithoutCategoryOrTypeCanBeDownloaded() throws Exception {
        FileRepository fileRepository = mock(FileRepository.class);
        FileCategoryRepository categoryRepository = mock(FileCategoryRepository.class);
        FileTypeRepository typeRepository = mock(FileTypeRepository.class);
        MinioService minioService = mock(MinioService.class);
        MinioConfig minioConfig = mock(MinioConfig.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        UUID fileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        byte[] bytes = "imported package file".getBytes(StandardCharsets.UTF_8);

        FileEntity file = new FileEntity();
        file.setId(fileId);
        file.setProjectId(projectId);
        file.setStatus("ACTIVE");
        file.setBucketName("archive-files");
        file.setObjectKey("import/package/project/file.txt");
        file.setSourceType("BRANCH_PACKAGE");

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.IMPORTED);

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(minioService.downloadFile("archive-files", "import/package/project/file.txt"))
                .thenReturn(new java.io.ByteArrayInputStream(bytes));

        FileService service = new FileService(
                fileRepository,
                categoryRepository,
                typeRepository,
                minioService,
                minioConfig,
                projectRepository,
                confidentiality,
                transactionManager
        );

        try (var stream = service.downloadFile(fileId)) {
            assertEquals("imported package file",
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }

        verify(minioService).downloadFile("archive-files", "import/package/project/file.txt");
        verifyNoInteractions(categoryRepository, typeRepository);
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
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

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
                confidentiality,
                transactionManager
        );

        assertThrows(EntityNotFoundException.class, () -> service.getFileMetadata(fileId));
        verifyNoInteractions(projectRepository, confidentiality, minioService);
    }
}
