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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final TransactionTemplate batchUploadTransaction;

    public FileService(FileRepository fileRepository,
                       FileCategoryRepository fileCategoryRepository,
                       FileTypeRepository fileTypeRepository,
                       MinioService minioService,
                       MinioConfig minioConfig,
                       ProjectRepository projectRepository,
                       ConfidentialityAccessService confidentialityAccessService,
                       PlatformTransactionManager transactionManager) {
        this.fileRepository = fileRepository;
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileTypeRepository = fileTypeRepository;
        this.minioService = minioService;
        this.minioConfig = minioConfig;
        this.projectRepository = projectRepository;
        this.confidentialityAccessService = confidentialityAccessService;
        this.batchUploadTransaction = new TransactionTemplate(transactionManager);
        this.batchUploadTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Page<FileResponse> listProjectFiles(UUID projectId, UUID categoryId, UUID typeId,
                                               String status, String search, int page, int size) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        if (project.getDiscardedAt() != null) {
            throw new ResourceNotFoundException("Project", "id", projectId);
        }
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

        List<FileCategory> categories = resolveActiveCategories(request.categoryIds(), request.categoryId());
        UUID primaryCategoryId = request.categoryId() != null
                ? request.categoryId()
                : categories.get(0).getId();
        if (categories.stream().noneMatch(category -> category.getId().equals(primaryCategoryId))) {
            throw new IllegalArgumentException("Primary category must be included in categoryIds");
        }

        String originalName = multipartFile.getOriginalFilename();
        String extension = extractExtension(originalName);
        FileType fileType = null;
        if (request.typeId() != null) {
            fileType = fileTypeRepository.findById(request.typeId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "File type not found with id: " + request.typeId()));
            if (!Boolean.TRUE.equals(fileType.getIsActive())) {
                throw new IllegalStateException("File type is inactive");
            }
            UUID selectedTypeCategoryId = fileType.getCategoryId();
            if (categories.stream().noneMatch(category -> category.getId().equals(selectedTypeCategoryId))) {
                throw new IllegalArgumentException(
                        "Selected file type does not belong to selected category");
            }
        } else if (extension != null) {
            // If the operator did not choose a type, infer it from the configured extension
            // inside the selected category. More than one match is ambiguous and must not be guessed.
            List<FileType> matches = fileTypeRepository.findByCategoryIdAndIsActive(primaryCategoryId, true)
                    .stream()
                    .filter(candidate -> extensionsEquivalent(extension, candidate.getDefaultExtension()))
                    .toList();
            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "أكثر من نوع ملف في التصنيف الحالي يستخدم الامتداد ." + extension
                                + ". اختر النوع يدوياً قبل الرفع");
            }
            if (matches.size() == 1) {
                fileType = matches.get(0);
            }
        }
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
            minioService.uploadFile(multipartFile, bucketName, objectKey, detectedMimeType);
            uploadedToObjectStorage = true;
            registerRollbackCleanup(bucketName, objectKey);

            FileEntity fileEntity = new FileEntity();
            fileEntity.setProjectId(projectId);
            fileEntity.setCategoryId(primaryCategoryId);
            fileEntity.setCategoryIds(categories.stream().map(FileCategory::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
            fileEntity.setTypeId(fileType != null ? fileType.getId() : null);
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

            FileEntity saved = fileRepository.saveAndFlush(fileEntity);
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FileBatchResponse uploadBatch(UUID projectId, List<MultipartFile> files,
                                         FileUploadRequest request, UUID uploadedBy) {
        List<FileResponse> successFiles = new ArrayList<>();
        List<FileBatchResponse.BatchError> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                FileResponse response = batchUploadTransaction.execute(status ->
                        uploadFile(projectId, file, request, uploadedBy));
                if (response == null) {
                    throw new IllegalStateException("File upload transaction returned no result");
                }
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

        UUID targetTypeId = request.typeId() != null ? request.typeId() : file.getTypeId();
        List<FileCategory> targetCategories = null;
        if ((request.categoryIds() != null && !request.categoryIds().isEmpty()) || request.categoryId() != null) {
            targetCategories = resolveActiveCategories(request.categoryIds(), request.categoryId());
        }
        Set<UUID> targetCategoryIds = targetCategories != null
                ? targetCategories.stream().map(FileCategory::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : file.getCategoryIds();
        UUID targetCategoryId = request.categoryId() != null
                ? request.categoryId()
                : targetCategories != null ? targetCategories.get(0).getId() : file.getCategoryId();
        if (!targetCategoryIds.contains(targetCategoryId)) {
            throw new IllegalArgumentException("Primary category must be included in categoryIds");
        }

        if (targetTypeId != null) {
            FileType targetType = fileTypeRepository.findById(targetTypeId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "File type not found with id: " + targetTypeId));
            if (!Boolean.TRUE.equals(targetType.getIsActive())) {
                throw new IllegalStateException("File type is inactive");
            }
            if (!targetCategoryIds.contains(targetType.getCategoryId())) {
                throw new IllegalArgumentException(
                        "Selected file type does not belong to selected category");
            }
        }

        if (targetCategories != null) {
            file.setCategoryId(targetCategoryId);
            file.setCategoryIds(targetCategoryIds);
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
        if ("QUARANTINED".equalsIgnoreCase(file.getStatus())) {
            throw new IllegalStateException("Quarantined originals require a separate retention review");
        }

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

    public InputStream downloadFileRange(UUID id, long offset, long length) {
        FileEntity file = getAccessibleFile(id);
        if (offset < 0 || length <= 0 || file.getSizeBytes() == null ||
                offset >= file.getSizeBytes() || length > file.getSizeBytes() - offset) {
            throw new IllegalArgumentException("Invalid file byte range");
        }
        try {
            return minioService.downloadRange(file.getBucketName(), file.getObjectKey(), offset, length);
        } catch (Exception e) {
            log.error("Failed to read file range: id={}", id, e);
            throw new RuntimeException("Failed to read file range", e);
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

    private List<FileCategory> resolveActiveCategories(List<UUID> requestedIds, UUID legacyCategoryId) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if (requestedIds != null) ids.addAll(requestedIds);
        if (ids.isEmpty() && legacyCategoryId != null) ids.add(legacyCategoryId);
        if (ids.isEmpty() || ids.contains(null)) {
            throw new IllegalArgumentException("اختر تصنيفاً واحداً على الأقل للملف");
        }
        List<FileCategory> categories = new ArrayList<>();
        for (UUID id : ids) {
            FileCategory category = fileCategoryRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + id));
            if (!Boolean.TRUE.equals(category.getIsActive())) {
                throw new IllegalStateException("File category is inactive: " + category.getName());
            }
            categories.add(category);
        }
        return categories;
    }

    private void registerRollbackCleanup(String bucketName, String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                            return;
                        }
                        try {
                            minioService.deleteFile(bucketName, objectKey);
                        } catch (Exception cleanupError) {
                            log.error(
                                    "Failed to remove MinIO object after transaction rollback: {}",
                                    objectKey,
                                    cleanupError
                            );
                        }
                    }
                }
        );
    }

    private Project requireMutableProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        if (project.getDiscardedAt() != null) {
            throw new ResourceNotFoundException("Project", "id", projectId);
        }
        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());

        if (project.getStatus() == Project.ProjectStatus.UNDER_REVIEW
                || project.getStatus() == Project.ProjectStatus.REJECTED
                || project.getStatus() == Project.ProjectStatus.APPROVED
                || project.getStatus() == Project.ProjectStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Project files cannot be modified when project status is " + project.getStatus());
        }
        return project;
    }

    private FileEntity getAccessibleFile(UUID id) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File not found with id: " + id));

        // Quarantined originals are not ordinary documents. All standard
        // metadata, preview, range and download routes share this guard.
        if (file.getDeletedAt() != null || "DELETED".equalsIgnoreCase(file.getStatus())
                || "QUARANTINED".equalsIgnoreCase(file.getStatus())) {
            throw new EntityNotFoundException("File not found with id: " + id);
        }

        Project project = projectRepository.findById(file.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", file.getProjectId()));
        if (project.getDiscardedAt() != null) {
            throw new ResourceNotFoundException("Project", "id", file.getProjectId());
        }

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

    private static boolean extensionsEquivalent(String actual, String configured) {
        if (actual == null || configured == null || actual.isBlank() || configured.isBlank()) return false;
        String left = actual.startsWith(".") ? actual.substring(1) : actual;
        String right = configured.startsWith(".") ? configured.substring(1) : configured;
        left = left.toLowerCase(java.util.Locale.ROOT);
        right = right.toLowerCase(java.util.Locale.ROOT);
        if (left.equals(right)) return true;
        return (left.equals("jpg") || left.equals("jpeg"))
                && (right.equals("jpg") || right.equals("jpeg"));
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
        FileCategory category = file.getCategoryId() != null
                ? fileCategoryRepository.findById(file.getCategoryId()).orElse(null)
                : null;
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
                file.getUpdatedAt(),
                file.getCategoryIds().stream().toList(),
                file.getCategoryIds().stream()
                        .map(id -> fileCategoryRepository.findById(id).map(FileCategory::getName).orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList()
        );
    }
}
