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
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

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
    private final CurrentUserService currentUser;
    private final JdbcTemplate jdbc;
    private static final long MAX_REVIEW_BYTES = 512L * 1024 * 1024;
    public record ReviewRequest(String decision, String note) {}
    public record ReviewResult(UUID fileId, String decision, String fileStatus) {}
    public record HistoryEntry(UUID id, UUID reviewedBy, String decision,
                               String note, LocalDateTime reviewedAt) {}

    public QuarantineService(FileRepository files, ProjectRepository projects,
                             ConfidentialityAccessService clearance, MinioService minio,
                             CurrentUserService currentUser, JdbcTemplate jdbc) {
        this.files = files;
        this.projects = projects;
        this.clearance = clearance;
        this.minio = minio;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    public Page<Entry> list(int page, int size) {
        if (page < 1) throw new IllegalArgumentException("Page must be >= 1");
        return files.findAccessibleQuarantined(
                clearance.currentMaxLevelOrder(),
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

    /** Read-only review trail for a quarantined or previously released migrated file. */
    public List<HistoryEntry> history(UUID fileId) {
        FileEntity file = files.findById(fileId)
                .orElseThrow(() -> new EntityNotFoundException("Quarantined file not found"));
        if (file.getDeletedAt() != null || !"MIGRATION".equals(file.getSourceType())
                || (!"QUARANTINED".equals(file.getStatus())
                    && !"ACTIVE".equals(file.getStatus()))) {
            throw new EntityNotFoundException("Quarantined file not found");
        }
        checkAccess(file);
        return jdbc.query("""
            SELECT id, reviewed_by, decision, note, reviewed_at
            FROM project.quarantine_reviews
            WHERE file_id = ?
            ORDER BY reviewed_at DESC, id DESC
            LIMIT 100
            """, (rs, rowNum) -> new HistoryEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("reviewed_by", UUID.class),
                rs.getString("decision"),
                rs.getString("note"),
                rs.getTimestamp("reviewed_at").toLocalDateTime()
            ), fileId);
    }

    /**
     * Every decision is preserved; RELEASE is deliberately fail-closed.
     * The file retains its original MinIO object and source checksum.
     */
    @Transactional
    public ReviewResult review(UUID id, ReviewRequest request) {
        if (request == null || request.decision() == null
                || request.note() == null || request.note().trim().length() < 12
                || request.note().length() > 1000) {
            throw new IllegalArgumentException("اكتب قرارًا ومبررًا من 12 إلى 1000 حرف");
        }
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!"KEEP".equals(decision) && !"RELEASE".equals(decision)) {
            throw new IllegalArgumentException("قرار المراجعة غير مدعوم");
        }
        FileEntity file = files.lockFile(id)
                .orElseThrow(() -> new EntityNotFoundException("Quarantined file not found"));
        if (file.getDeletedAt() != null || !"QUARANTINED".equals(file.getStatus())
                || !"MIGRATION".equals(file.getSourceType())) {
            throw new EntityNotFoundException("Quarantined file not found");
        }
        checkAccess(file);
        if ("RELEASE".equals(decision)) {
            verifyBeforeRelease(file);
            file.setStatus("ACTIVE");
            // Retain the original isolation reason for downstream forensic audit.
            files.saveAndFlush(file);
        }
        jdbc.update("""
            INSERT INTO project.quarantine_reviews
              (id, file_id, reviewed_by, decision, note, reviewed_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), id, currentUser.userId(), decision, request.note().trim());
        return new ReviewResult(id, decision, file.getStatus());
    }

    private void verifyBeforeRelease(FileEntity file) {
        String expectedChecksum = file.getChecksumSha256();
        if (expectedChecksum == null || !expectedChecksum.matches("[a-fA-F0-9]{64}")) {
            throw new BusinessRuleException("لا يمكن الإفراج دون بصمة SHA-256 أصلية معروفة");
        }
        long expectedSize = file.getSizeBytes() == null ? -1 : file.getSizeBytes();
        if (expectedSize < 1 || expectedSize > MAX_REVIEW_BYTES) {
            throw new BusinessRuleException("حجم الملف غير صالح للمراجعة التلقائية؛ أبقه معزولًا");
        }
        Path spool = null;
        try {
            spool = Files.createTempFile("archive-quarantine-review-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (InputStream input = new DigestInputStream(
                    minio.downloadFile(file.getBucketName(), file.getObjectKey()), digest);
                 OutputStream output = Files.newOutputStream(spool)) {
                byte[] block = new byte[65536];
                int count;
                while ((count = input.read(block)) != -1) {
                    copied += count;
                    if (copied > MAX_REVIEW_BYTES || copied > expectedSize) {
                        throw new BusinessRuleException("حجم الملف المخزن غير متطابق مع السجل");
                    }
                    output.write(block, 0, count);
                }
            }
            if (copied != expectedSize || !MessageDigest.isEqual(
                    HexFormat.of().parseHex(expectedChecksum),
                    digest.digest())) {
                throw new BusinessRuleException("فشل تطابق الحجم أو بصمة SHA-256؛ يبقى الملف معزولًا");
            }
            byte[] prefix;
            try (InputStream input = Files.newInputStream(spool)) {
                prefix = input.readNBytes(FileContentInspector.SNIFF_BYTES);
            }
            String extension = FileContentInspector.extensionFromName(file.getOriginalName());
            FileContentInspector.LegacyAssessment assessment =
                    FileContentInspector.assessLegacyEntry(prefix, extension, spool);
            if (assessment.quarantined()) {
                throw new BusinessRuleException("فشل الفحص: " + assessment.reason()
                        + ". احتفظ بالملف معزولًا");
            }
            file.setMimeType(assessment.mimeType());
            // Never alter the recorded content or MinIO key when changing status.
        } catch (BusinessRuleException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessRuleException("تعذر إجراء فحص الملف؛ لا يمكن الإفراج عنه");
        } finally {
            if (spool != null) {
                try { Files.deleteIfExists(spool); } catch (IOException ignored) {}
            }
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
