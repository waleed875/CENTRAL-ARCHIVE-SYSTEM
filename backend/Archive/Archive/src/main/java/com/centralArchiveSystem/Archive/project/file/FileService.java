package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.project.file.dto.*;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileType;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final FileRepository fileRepository;
    private final FileCategoryRepository fileCategoryRepository;
    private final FileTypeRepository fileTypeRepository;
    private final MinioService minioService;
    private final MinioConfig minioConfig;
    private final ProjectRepository projectRepository;
    private final ConfidentialityAccessService confidentialityAccessService;

    public FileService(FileRepository fileRepository,
                       FileCategoryRepository fileCategoryRepository,
                       FileTypeRepository fileTypeRepository,
                       MinioService minioService,
                       MinioConfig minioConfig,
                       ProjectRepository projectRepository,
                       ConfidentialityAccessService confidentialityAccessService) {
        this.fileRepository = fileRepository;
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileTypeRepository = fileTypeRepository;
        this.minioService = minioService;
        this.minioConfig = minioConfig;
        this.projectRepository = projectRepository;
        this.confidentialityAccessService = confidentialityAccessService;
    }

    public Page<FileResponse> listProjectFiles(UUID projectId, UUID categoryId, UUID typeId,
                                               String status, String search, int page, int size) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());

        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        return fileRepository.searchAccessibleProjectFiles(
                projectId,
                categoryId,
                typeId,
                status,
                search,
                maxLevelOrder,
                pageable
        ).map(this::toResponse);
    }

    public FileResponse getFileMetadata(UUID id) {
        return toResponse(getAccessibleFile(id));
    }

    @Transactional
    public FileResponse uploadFile(UUID projectId, MultipartFile multipartFile,
                                   FileUploadRequest request, UUID uploadedBy) {
        requireMutableProject(projectId);
        confidentialityAccessService.requireReadAccess(request.confidentialityLevelId());

        FileCategory category = fileCategoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + request.categoryId()));
        if (!Boolean.TRUE.equals(category.getIsActive())) {
            throw new IllegalStateException("File category is inactive");
        }

        FileType fileType = null;
        if (request.typeId() != null) {
            fileType = fileTypeRepository.findById(request.typeId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "File type not found with id: " + request.typeId()));
            if (!Boolean.TRUE.equals(fileType.getIsActive())) {
                throw new IllegalStateException("File type is inactive");
            }
            if (!request.categoryId().equals(fileType.getCategoryId())) {
                throw new IllegalArgumentException(
                        "Selected file type does not belong to selected category");
            }
        }

        String originalName = multipartFile.getOriginalFilename();
        String extension = extractExtension(originalName);
        String expectedExtension = fileType != null
                && fileType.getDefaultExtension() != null
                && !fileType.getDefaultExtension().isBlank()
                        ? fileType.getDefaultExtension()
                        : extension;
        String detectedMimeType = FileContentInspector.validateMultipart(
                multipartFile,
                expectedExtension
        );
        String sourceType = "MANUAL";

        String storedName = UUID.randomUUID() + (extension != null ? "." + extension : "");
        String bucketName = minioConfig.getBucket();
        String objectKey = "projects/" + projectId + "/" + storedName;

        String checksum;
        try {
            checksum = calculateChecksum(multipartFile);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to calculate file checksum", e);
        }

        if (fileRepository.existsByProjectIdAndChecksumSha256AndDeletedAtIsNull(
                projectId,
                checksum
        )) {
            throw new IllegalArgumentException(
                    "A file with the same content already exists in this project");
        }

        boolean uploadedToObjectStorage = false;
        try {
            minioService.uploadFile(multipartFile, bucketName, objectKey);
            uploadedToObjectStorage = true;

            FileEntity fileEntity = new FileEntity();
            fileEntity.setProjectId(projectId);
            fileEntity.setCategoryId(request.categoryId());
            fileEntity.setTypeId(request.typeId());
            fileEntity.setConfidentialityLevelId(request.confidentialityLevelId());
            fileEntity.setOriginalName(originalName);
            fileEntity.setStoredName(storedName);
            fileEntity.setBucketName(bucketName);
            fileEntity.setObjectKey(objectKey);
            fileEntity.setExtension(extension);
            fileEntity.setMimeType(detectedMimeType);
            fileEntity.setSizeBytes(multipartFile.getSize());
            fileEntity.setChecksumSha256(checksum);
            fileEntity.setStatus("ACTIVE");
            fileEntity.setUploadedBy(uploadedBy);
            fileEntity.setSourceType(sourceType);
            fileEntity.setSourceReference(null);

            FileEntity saved = fileRepository.save(fileEntity);
            log.info("File uploaded successfully: id={}, originalName={}", saved.getId(), originalName);
            return toResponse(saved);

        } catch (Exception e) {
            if (uploadedToObjectStorage) {
                try {
                    minioService.deleteFile(bucketName, objectKey);
                } catch (Exception cleanupError) {
                    log.error("Failed to remove orphan object after database failure: {}", objectKey, cleanupError);
                }
            }

            log.error("Failed to upload file: {}", originalName, e);
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }
    }

    @Transactional
    public FileBatchResponse uploadBatch(UUID projectId, List<MultipartFile> files,
                                         FileUploadRequest request, UUID uploadedBy) {
        List<FileResponse> successFiles = new ArrayList<>();
        List<FileBatchResponse.BatchError> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                FileResponse response = uploadFile(projectId, file, request, uploadedBy);
                successFiles.add(response);
            } catch (Exception e) {
                errors.add(new FileBatchResponse.BatchError(
                        file.getOriginalFilename(),
                        e.getMessage()
                ));
            }
        }

        return new FileBatchResponse(
                files.size(),
                successFiles.size(),
                errors.size(),
                successFiles,
                errors
        );
    }

    @Transactional
    public FileResponse updateFileMetadata(UUID id, FileMetadataUpdateRequest request) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File not found with id: " + id));
        requireMutableProject(file.getProjectId());
        confidentialityAccessService.requireReadAccess(file.getConfidentialityLevelId());
        if (request.confidentialityLevelId() != null) {
            confidentialityAccessService.requireReadAccess(request.confidentialityLevelId());
        }

        UUID targetCategoryId = request.categoryId() != null
                ? request.categoryId()
                : file.getCategoryId();
        UUID targetTypeId = request.typeId() != null
                ? request.typeId()
                : file.getTypeId();

        if (request.categoryId() != null) {
            FileCategory targetCategory = fileCategoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "File category not found with id: " + request.categoryId()));
            if (!Boolean.TRUE.equals(targetCategory.getIsActive())) {
                throw new IllegalStateException("File category is inactive");
            }
        }

        if (targetTypeId != null) {
            FileType targetType = fileTypeRepository.findById(targetTypeId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "File type not found with id: " + targetTypeId));
            if (!Boolean.TRUE.equals(targetType.getIsActive())) {
                throw new IllegalStateException("File type is inactive");
            }
            if (!targetCategoryId.equals(targetType.getCategoryId())) {
                throw new IllegalArgumentException(
                        "Selected file type does not belong to selected category");
            }
        }

        if (request.categoryId() != null) {
            file.setCategoryId(request.categoryId());
        }
        if (request.typeId() != null) {
            file.setTypeId(request.typeId());
        }

        if (request.confidentialityLevelId() != null) {
            file.setConfidentialityLevelId(request.confidentialityLevelId());
        }

        FileEntity saved = fileRepository.save(file);
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id, UUID deletedBy, String reason) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File not found with id: " + id));
        requireMutableProject(file.getProjectId());
        confidentialityAccessService.requireReadAccess(file.getConfidentialityLevelId());

        file.setStatus("DELETED");
        file.setDeletedAt(LocalDateTime.now());
        file.setDeletedBy(deletedBy);
        file.setDeleteReason(reason);

        fileRepository.save(file);
        log.info("File soft deleted: id={}, deletedBy={}", id, deletedBy);
    }

    public InputStream downloadFile(UUID id) {
        FileEntity file = getAccessibleFile(id);

        try {
            return minioService.downloadFile(file.getBucketName(), file.getObjectKey());
        } catch (Exception e) {
            log.error("Failed to download file: id={}", id, e);
            throw new RuntimeException("Failed to download file: " + e.getMessage(), e);
        }
    }

    public String getFileName(UUID id) {
        return getAccessibleFile(id).getOriginalName();
    }

    public String getFileMimeType(UUID id) {
        return getAccessibleFile(id).getMimeType();
    }

    @Transactional
    public FileResponse restoreFile(UUID id) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File not found with id: " + id));
        requireMutableProject(file.getProjectId());
        confidentialityAccessService.requireReadAccess(file.getConfidentialityLevelId());

        if (file.getDeletedAt() == null) {
            throw new IllegalStateException("File is not deleted and cannot be restored");
        }

        file.setStatus("ACTIVE");
        file.setDeletedAt(null);
        file.setDeletedBy(null);
        file.setDeleteReason(null);

        FileEntity saved = fileRepository.save(file);
        log.info("File restored: id={}", id);
        return toResponse(saved);
    }

    private Project requireMutableProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());

        if (project.getStatus() == Project.ProjectStatus.APPROVED
                || project.getStatus() == Project.ProjectStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Project files cannot be modified when project status is " + project.getStatus());
        }
        return project;
    }

    private FileEntity getAccessibleFile(UUID id) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File not found with id: " + id));

        if (file.getDeletedAt() != null || "DELETED".equalsIgnoreCase(file.getStatus())) {
            throw new EntityNotFoundException("File not found with id: " + id);
        }

        Project project = projectRepository.findById(file.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", file.getProjectId()));

        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());
        confidentialityAccessService.requireReadAccess(file.getConfidentialityLevelId());
        return file;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return null;
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) return null;
        return fileName.substring(lastDotIndex + 1).toLowerCase();
    }

    private String calculateChecksum(MultipartFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];

        try (InputStream input = file.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private FileResponse toResponse(FileEntity file) {
        FileCategory category = fileCategoryRepository.findById(file.getCategoryId()).orElse(null);
        FileType fileType = file.getTypeId() != null
                ? fileTypeRepository.findById(file.getTypeId()).orElse(null)
                : null;

        return new FileResponse(
                file.getId(),
                file.getProjectId(),
                file.getCategoryId(),
                category != null ? category.getCode() : null,
                category != null ? category.getName() : null,
                file.getTypeId(),
                fileType != null ? fileType.getCode() : null,
                fileType != null ? fileType.getName() : null,
                file.getConfidentialityLevelId(),
                null,
                file.getOriginalName(),
                file.getStoredName(),
                file.getBucketName(),
                file.getObjectKey(),
                file.getExtension(),
                file.getMimeType(),
                file.getSizeBytes(),
                file.getChecksumSha256(),
                file.getStatus(),
                file.getUploadedBy(),
                null,
                file.getSourceType(),
                file.getSourceReference(),
                file.getDeletedAt(),
                file.getDeletedBy(),
                file.getDeleteReason(),
                file.getCreatedAt(),
                file.getUpdatedAt()
        );
    }
}
