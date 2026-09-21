package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

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
