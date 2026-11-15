package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only audit of migration link integrity, with at most fifteen MinIO HEAD
 * requests to sample stored objects. This never attaches or deletes any file.
 */
@Service
public class MigrationFileAuditService {
    public record LinkProblem(String sourceKey, String trackingStatus,
                              UUID targetId, String reason) {}
    public record StorageSample(String sourceKey, UUID fileId, String objectStatus) {}
    public record Report(UUID sourceId, Map<String, Long> trackingStatuses,
                         long missingOrInvalidDoneLinks, List<LinkProblem> problems,
                         List<StorageSample> storageSamples, boolean problemSamplesLimited,
                         String warning) {}

    private final MigrationSourceRepository sources;
    private final LegacyMigrationItemRepository items;
    private final MinioService minio;

    public MigrationFileAuditService(MigrationSourceRepository sources,
                                     LegacyMigrationItemRepository items,
                                     MinioService minio) {
        this.sources = sources;
        this.items = items;
        this.minio = minio;
    }

    @Transactional(readOnly = true)
    public Report audit(UUID sourceId) {
        if (!sources.existsById(sourceId)) {
            throw new ResourceNotFoundException("MigrationSource", "id", sourceId);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        long invalidDone = 0;
        for (Object[] row : items.auditFileLinkCounts(sourceId)) {
            String status = String.valueOf(row[0]);
            counts.put(status, ((Number) row[1]).longValue());
            invalidDone += ((Number) row[2]).longValue();
        }

        List<LinkProblem> problems = new ArrayList<>();
        for (Object[] row : items.sampleFileLinkProblems(sourceId)) {
            problems.add(new LinkProblem(String.valueOf(row[0]), String.valueOf(row[1]),
                    (UUID)row[2], row[3] == null ? "" : String.valueOf(row[3])));
        }

        List<StorageSample> samples = new ArrayList<>();
        for (Object[] row : items.sampleLinkedStorageObjects(sourceId)) {
            String state;
            try {
                state = minio.objectExists(String.valueOf(row[2]), String.valueOf(row[3]))
                        ? "PRESENT" : "MISSING";
            } catch (Exception error) {
                state = "UNKNOWN";
            }
            samples.add(new StorageSample(String.valueOf(row[0]), (UUID)row[1], state));
        }
        return new Report(sourceId, counts, invalidDone, problems, samples,
                problems.size() >= 50,
                "DB link counts cover all tracked files. MinIO existence is sampled only; " +
                "unlinked/orphan MinIO objects cannot be discovered by this API.");
    }
}
