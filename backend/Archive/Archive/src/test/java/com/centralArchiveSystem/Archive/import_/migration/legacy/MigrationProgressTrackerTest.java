package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MigrationProgressTrackerTest {
    @Test
    void countsOnlyCompletedFilesAndScopesTelemetryToBatchAndSource() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC);
        MigrationProgressTracker tracker = new MigrationProgressTracker(clock);
        UUID batch = UUID.randomUUID(), source = UUID.randomUUID();
        tracker.start(batch, source, 10);
        tracker.fileStarted(source, "FILE-341");

        assertNull(tracker.snapshot(batch, UUID.randomUUID()));
        assertNull(tracker.snapshot(UUID.randomUUID(), source));

        var before = tracker.snapshot(batch, source);
        assertEquals(0, before.completedFiles());
        assertEquals(0, before.transferredBytes());
        assertTrue(before.activeSourceFiles().contains("FILE-341"));

        tracker.completed(source, 1048576, 2_000_000, 3_000_000, 1_000_000);
        tracker.fileFinished(source, "FILE-341");
        var sample = tracker.snapshot(batch, source);
        assertEquals(1, sample.completedFiles());
        assertEquals(1048576, sample.transferredBytes());
        assertEquals(12.0, sample.filesPerMinute(), 0.01);
        assertEquals(0.2, sample.mibPerSecond(), 0.001);
        assertEquals(2.0, sample.averageReadDecodeMs(), 0.001);
        assertEquals(3.0, sample.averageUploadMs(), 0.001);
        assertEquals(1.0, sample.averageMetadataMs(), 0.001);
        assertTrue(sample.activeSourceFiles().isEmpty());
        assertNull(sample.estimatedSecondsRemaining(), "insufficient data to estimate remaining time");

        tracker.completed(source, 100, 0, 0, 0);
        tracker.completed(source, 100, 0, 0, 0);
        assertNotNull(tracker.snapshot(batch, source).estimatedSecondsRemaining());
        tracker.finish(batch, source);
        assertNull(tracker.snapshot(batch, source));
    }

    @Test
    void detectsStalledFileAndTrimsOldSamples() {
        var time = new AtomicReference<>(Instant.parse("2026-11-18T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        MigrationProgressTracker tracker = new MigrationProgressTracker(clock);
        UUID batch = UUID.randomUUID(), source = UUID.randomUUID();
        tracker.start(batch, source, 5);
        tracker.fileStarted(source, "FILE-4004");
        tracker.completed(source, 1000, 1, 1, 1);

        time.set(time.get().plusSeconds(121));
        var snapshot = tracker.snapshot(batch, source);
        assertTrue(snapshot.stalled());
        assertEquals(0, snapshot.filesPerMinute(), 0.001);
        assertEquals(0, snapshot.mibPerSecond(), 0.001);
        assertEquals(121L, snapshot.secondsSinceLastCompletion());
        tracker.fileFinished(source, "FILE-4004");
        assertFalse(tracker.snapshot(batch, source).stalled());
    }

    @Test
    void staleFinishCannotClearNewRunForSameSource() {
        MigrationProgressTracker tracker = new MigrationProgressTracker();
        UUID batch1 = UUID.randomUUID(), batch2 = UUID.randomUUID(), source = UUID.randomUUID();
        tracker.start(batch1, source, 100);
        tracker.start(batch2, source, 25);
        tracker.finish(batch1, source);
        assertNull(tracker.snapshot(batch1, source));
        assertNotNull(tracker.snapshot(batch2, source));
        tracker.finish(batch2, source);
        assertNull(tracker.snapshot(batch2, source));
    }
}
