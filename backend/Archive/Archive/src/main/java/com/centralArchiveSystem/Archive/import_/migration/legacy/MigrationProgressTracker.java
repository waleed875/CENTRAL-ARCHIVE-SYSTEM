package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, process-local telemetry. This is intentionally not used as the
 * durable source of truth for migration completion or idempotency. Existing
 * import.legacy_migration_items remain authoritative after restarts.
 *
 * The HTTP endpoint returns a sample only for the exact batch and source pair;
 * no per-request database scans or file contents are involved.
 */
@Service
public class MigrationProgressTracker {
    private static final long WINDOW_SECONDS = 60;
    private static final int MAX_SAMPLES = 2048;

    public record Snapshot(UUID batchId, UUID sourceId, long completedFiles,
                           long transferredBytes, double filesPerMinute,
                           double mibPerSecond, Long estimatedSecondsRemaining,
                           Long secondsSinceLastCompletion, boolean stalled,
                           List<String> activeSourceFiles,
                           double averageReadDecodeMs, double averageUploadMs,
                           double averageMetadataMs) {}

    private record Sample(Instant when, long bytes) {}

    private final ConcurrentHashMap<UUID, Run> bySource = new ConcurrentHashMap<>();
    private final Clock clock;

    public MigrationProgressTracker() { this(Clock.systemUTC()); }

    MigrationProgressTracker(Clock clock) { this.clock = clock; }

    public void start(UUID batchId, UUID sourceId, long filesToProcess) {
        Run run = new Run(batchId, sourceId, clock.instant(), Math.max(0, filesToProcess));
        // Only one batch may be active per source. Replaces stale process-local
        // telemetry, never changes database tracking rows.
        bySource.put(sourceId, run);
    }

    public void fileStarted(UUID sourceId, String sourceFileKey) {
        Run run = bySource.get(sourceId);
        if (run != null) synchronized (run) {
            if (run.active.size() < 16) run.active.add(sourceFileKey);
        }
    }

    public void fileFinished(UUID sourceId, String sourceFileKey) {
        Run run = bySource.get(sourceId);
        if (run != null) synchronized (run) { run.active.remove(sourceFileKey); }
    }

    public void completed(UUID sourceId, long decodedBytes,
                          long readDecodeNanos, long uploadNanos, long metadataNanos) {
        Run run = bySource.get(sourceId);
        if (run == null) return;
        synchronized (run) {
            Instant now = clock.instant();
            run.completed++;
            run.bytes += Math.max(0, decodedBytes);
            run.lastSuccess = now;
            run.readDecodeNanos += Math.max(0, readDecodeNanos);
            run.uploadNanos += Math.max(0, uploadNanos);
            run.metadataNanos += Math.max(0, metadataNanos);
            run.recent.addLast(new Sample(now, Math.max(0, decodedBytes)));
            trim(run, now);
        }
    }

    public Snapshot snapshot(UUID batchId, UUID sourceId) {
        Run run = bySource.get(sourceId);
        if (run == null || !run.batchId.equals(batchId)) return null;
        synchronized (run) {
            Instant now = clock.instant();
            trim(run, now);
            long recentBytes = 0;
            for (Sample sample : run.recent) recentBytes += sample.bytes;
            // A minimum five-second divisor prevents a misleading instant spike.
            long elapsed = Math.max(5, Math.min(WINDOW_SECONDS,
                    java.time.Duration.between(run.started, now).getSeconds()));
            double filesPerMinute = run.recent.size() * 60.0 / elapsed;
            double mibPerSecond = recentBytes / 1048576.0 / elapsed;
            Long idle = run.lastSuccess == null ? null :
                    Math.max(0, java.time.Duration.between(run.lastSuccess, now).getSeconds());
            boolean stalled = !run.active.isEmpty()
                    && java.time.Duration.between(
                            run.lastSuccess == null ? run.started : run.lastSuccess, now)
                    .getSeconds() >= 120;
            Long eta = null;
            long remaining = Math.max(0, run.filesToProcess - run.completed);
            if (run.completed >= 3 && filesPerMinute > 0 && remaining > 0) {
                eta = (long) Math.ceil(remaining * 60.0 / filesPerMinute);
            }
            long count = Math.max(1, run.completed);
            return new Snapshot(run.batchId, run.sourceId, run.completed, run.bytes,
                    filesPerMinute, mibPerSecond, eta, idle, stalled,
                    List.copyOf(run.active),
                    run.readDecodeNanos / 1_000_000.0 / count,
                    run.uploadNanos / 1_000_000.0 / count,
                    run.metadataNanos / 1_000_000.0 / count);
        }
    }

    public void finish(UUID batchId, UUID sourceId) {
        Run run = bySource.get(sourceId);
        if (run != null && run.batchId.equals(batchId)) bySource.remove(sourceId, run);
    }

    private static void trim(Run run, Instant now) {
        Instant cutoff = now.minusSeconds(WINDOW_SECONDS);
        while (!run.recent.isEmpty()
                && (run.recent.peekFirst().when.isBefore(cutoff)
                    || run.recent.size() > MAX_SAMPLES)) run.recent.removeFirst();
    }

    private static final class Run {
        final UUID batchId, sourceId;
        final Instant started;
        final long filesToProcess;
        final ArrayDeque<Sample> recent = new ArrayDeque<>();
        final Set<String> active = new HashSet<>();
        Instant lastSuccess;
        long completed, bytes, readDecodeNanos, uploadNanos, metadataNanos;

        Run(UUID batchId, UUID sourceId, Instant started, long filesToProcess) {
            this.batchId = batchId;
            this.sourceId = sourceId;
            this.started = started;
            this.filesToProcess = filesToProcess;
        }
    }
}
