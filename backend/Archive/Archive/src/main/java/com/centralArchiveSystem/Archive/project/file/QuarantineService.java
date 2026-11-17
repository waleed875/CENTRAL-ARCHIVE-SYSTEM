package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Administrative inventory of preserved original bytes; not part of the
 * regular file/preview API. Quarantine content is never rendered or executed.
 */
@Service
@Transactional(readOnly = true)
public class QuarantineService {
    public record Entry(UUID id, UUID projectId, String originalName,
                        String sourceReference, long sizeBytes, String checksumSha256,
                        String quarantineReason, LocalDateTime createdAt) {}

    private final FileRepository files;
    private final ProjectRepository projects;
    private final ConfidentialityAccessService clearance;
    private final MinioService minio;

    public QuarantineService(FileRepository files, ProjectRepository projects,
                             ConfidentialityAccessService clearance, MinioService minio) {
        this.files = files;
        this.projects = projects;
        this.clearance = clearance;
        this.minio = minio;
    }

    public Page<Entry> list(int page, int size) {
        if (page < 1) throw new IllegalArgumentException("Page must be >= 1");
        return files.findByStatusAndSourceTypeAndDeletedAtIsNull(
                "QUARANTINED", "MIGRATION",
                PageRequest.of(page - 1, Math.max(1, Math.min(100, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(file -> {
                    checkAccess(file);
                    return entry(file);
                });
    }

    public Entry get(UUID id) { return entry(require(id)); }

    public InputStream openForForensicReview(UUID id) {
        FileEntity file = require(id);
        try {
            return minio.downloadFile(file.getBucketName(), file.getObjectKey());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read preserved quarantine bytes", e);
        }
    }

    private FileEntity require(UUID id) {
        FileEntity file = files.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Quarantined file not found"));
        if (file.getDeletedAt() != null
                || !"QUARANTINED".equals(file.getStatus())
                || !"MIGRATION".equals(file.getSourceType())) {
            throw new EntityNotFoundException("Quarantined file not found");
        }
        checkAccess(file);
        return file;
    }

    private void checkAccess(FileEntity file) {
        Project project = projects.findById(file.getProjectId())
                .orElseThrow(() -> new EntityNotFoundException("Quarantined project not found"));
        if (project.getDiscardedAt() != null) {
            throw new EntityNotFoundException("Quarantined project not found");
        }
        clearance.requireReadAccess(project.getConfidentialityLevelId());
        clearance.requireReadAccess(file.getConfidentialityLevelId());
    }

    private static Entry entry(FileEntity file) {
        return new Entry(file.getId(), file.getProjectId(), file.getOriginalName(),
                file.getSourceReference(), file.getSizeBytes() == null ? 0 : file.getSizeBytes(),
                file.getChecksumSha256(), file.getQuarantineReason(), file.getCreatedAt());
    }
}
