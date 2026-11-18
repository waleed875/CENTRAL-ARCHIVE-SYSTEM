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
                         String warning, Map<String, Long> failedErrorCategories) {}

    /**
     * One bounded reconciliation page. The caller supplies the previous nextCursor
     * to continue; no server job, deletion, or expensive bucket listing is started.
     */
    public record ReconciliationFinding(String sourceKey, UUID targetId, String status) {}
    public record ReconciliationPage(UUID sourceId, int checked, long present,
                                     long missingObjects, long invalidLinks, long unknown,
                                     List<ReconciliationFinding> findings,
                                     String nextCursor, boolean hasMore,
                                     String limitation) {}

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

        Map<String, Long> errorCategories = new LinkedHashMap<>();
        for (Object[] row : items.countFailedFileErrorCategories(sourceId)) {
            errorCategories.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
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
                "unlinked/orphan MinIO objects cannot be discovered by this API.",
                errorCategories);
    }
    /**
     * Checks at most 100 source-scoped DONE links and their MinIO object existence.
     * Missing or invalid PostgreSQL links are reported without querying MinIO.
     * Network and authorization exceptions are UNKNOWN, never missing.
     *
     * This does not discover source records that were never tracked, nor unlinked
     * MinIO objects; those require a separate inventory-based reconciliation.
     */
    @Transactional(readOnly = true)
    public ReconciliationPage reconcilePage(UUID sourceId, String afterKey, int requestedLimit) {
        if (!sources.existsById(sourceId)) {
            throw new ResourceNotFoundException("MigrationSource", "id", sourceId);
        }
        if (requestedLimit < 1 || requestedLimit > 100) {
            throw new IllegalArgumentException("Reconciliation page size must be between 1 and 100");
        }
        if (afterKey != null && !afterKey.matches("FILE-[0-9]{1,18}")) {
            throw new IllegalArgumentException("Invalid file reconciliation cursor");
        }

        List<Object[]> rows = items.reconciliationPage(sourceId, afterKey, requestedLimit + 1);
        boolean more = rows.size() > requestedLimit;
        int checked = Math.min(rows.size(), requestedLimit);
        long present = 0, missing = 0, invalid = 0, unknown = 0;
        List<ReconciliationFinding> findings = new ArrayList<>();
        String nextCursor = null;

        for (int index = 0; index < checked; index++) {
            Object[] row = rows.get(index);
            String key = String.valueOf(row[0]);
            nextCursor = key;
            UUID targetId = (UUID) row[1];
            String result;
            if (targetId == null || row[2] == null || row[3] == null || row[4] == null
                    || row[5] != null || row[6] == null
                    || (!"ACTIVE".equalsIgnoreCase(String.valueOf(row[6]))
                        && !"QUARANTINED".equalsIgnoreCase(String.valueOf(row[6])))) {
                result = "INVALID_LINK";
                invalid++;
            } else if (row[7] == null || String.valueOf(row[7]).isBlank()
                    || row[8] == null || String.valueOf(row[8]).isBlank()) {
                result = "INVALID_LINK";
                invalid++;
            } else {
                try {
                    if (minio.objectExists(String.valueOf(row[7]), String.valueOf(row[8]))) {
                        result = "PRESENT";
                        present++;
                    } else {
                        result = "MISSING_OBJECT";
                        missing++;
                    }
                } catch (Exception e) {
                    result = "UNKNOWN";
                    unknown++;
                }
            }
            // Healthy entries count toward progress but do not bloat the response.
            if (!"PRESENT".equals(result)) {
                findings.add(new ReconciliationFinding(key, targetId, result));
            }
        }
        return new ReconciliationPage(sourceId, checked, present, missing, invalid, unknown,
                List.copyOf(findings), more ? nextCursor : null, more,
                "This page checks tracked DONE files only. It cannot detect untracked legacy files "
                        + "or orphan objects in MinIO; no data was modified.");
    }

}
