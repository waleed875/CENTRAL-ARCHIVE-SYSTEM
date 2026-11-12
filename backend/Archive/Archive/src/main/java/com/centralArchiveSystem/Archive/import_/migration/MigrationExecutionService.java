package com.centralArchiveSystem.Archive.import_.migration;

import com.centralArchiveSystem.Archive.import_.migration.legacy.MigrationRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MigrationExecutionService {

    private final MigrationRunner migrationRunner;
    private final ConcurrentMap<UUID, MigrationCancellationToken> cancellationTokens =
            new ConcurrentHashMap<>();

    public MigrationExecutionService(MigrationRunner migrationRunner) {
        this.migrationRunner = migrationRunner;
    }

    public void prepare(UUID batchId) {
        cancellationTokens.computeIfAbsent(batchId, ignored -> new MigrationCancellationToken());
    }

    /**
     * Returns false when no worker is registered in this application process. In that case the
     * caller may safely finalize a stale RUNNING row while preserving the durable item mappings.
     */
    public boolean requestCancellation(UUID batchId) {
        MigrationCancellationToken token = cancellationTokens.get(batchId);
        if (token == null) {
            return false;
        }
        token.requestCancellation();
        return true;
    }

    public String testConnection(UUID sourceId) {
        return migrationRunner.testConnection(sourceId);
    }

    @Async
    public void execute(UUID batchId) {
        MigrationCancellationToken token = cancellationTokens.computeIfAbsent(
                batchId, ignored -> new MigrationCancellationToken());
        try {
            migrationRunner.runBatch(batchId, token);
        } finally {
            cancellationTokens.remove(batchId, token);
        }
    }
}
