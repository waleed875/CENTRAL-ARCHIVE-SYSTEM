package com.centralArchiveSystem.Archive.import_.migration.legacy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative stop signal for a single migration batch.
 *
 * <p>Workers inspect the signal only between projects and files. An already-started file is
 * allowed to finish its upload and metadata transaction (or run the existing cleanup path) before
 * the worker observes the request again.
 */
public final class MigrationCancellationToken {

    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

    public void requestCancellation() {
        cancellationRequested.set(true);
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }
}
