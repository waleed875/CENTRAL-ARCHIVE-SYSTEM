package com.centralArchiveSystem.Archive.import_.migration;

import com.centralArchiveSystem.Archive.import_.migration.legacy.MigrationRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MigrationExecutionService {

    private final MigrationRunner migrationRunner;

    public MigrationExecutionService(MigrationRunner migrationRunner) {
        this.migrationRunner = migrationRunner;
    }

    public String testConnection(UUID sourceId) {
        return migrationRunner.testConnection(sourceId);
    }

    @Async
    public void execute(UUID batchId) {
        migrationRunner.runBatch(batchId);
    }
}
