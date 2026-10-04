package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.import_.migration.MigrationBatchRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSource;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.import_.migration.setting.MigrationSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which migration source the legacy migration reads from, and what the source it invents is stamped
 * with.
 *
 * <p>Both questions are silent-failure questions. Reading the wrong source does not raise anything:
 * the run reports zero files and a SUCCESS batch, and an operator concludes the legacy database was
 * empty. That is what the two cases below are guarding.
 */
class MigrationRunnerTest {

    private static final String URL = "jdbc:postgresql://localhost:5433/ARC2";

    /** Only the source lookup matters here, so the other collaborators are mocks. */
    private static MigrationRunner runner(MigrationSourceRepository sources, String jdbcUrl) {
        MigrationRunner runner = new MigrationRunner(
                mock(LegacyMigrationService.class),
                mock(MigrationBatchRepository.class),
                sources,
                mock(LegacyMigrationItemRepository.class),
                mock(MigrationSettingsService.class));
        ReflectionTestUtils.setField(runner, "sourceName", "ARC2");
        ReflectionTestUtils.setField(runner, "legacyJdbcUrl", jdbcUrl);
        return runner;
    }

    private static MigrationSource source(String name) {
        MigrationSource source = new MigrationSource();
        source.setId(UUID.randomUUID());
        source.setName(name);
        return source;
    }

    private static MigrationSourceRepository repositoryReturning(MigrationSource... found) {
        MigrationSourceRepository sources = mock(MigrationSourceRepository.class);
        when(sources.findByNameContainingIgnoreCase("ARC2")).thenReturn(List.of(found));
        return sources;
    }

    // ---------------------------------------------------------------- source selection

    @Test
    void prefersTheExactNameOverAMerelySimilarOne() {
        MigrationSource exact = source("ARC2");
        MigrationSourceRepository sources =
                repositoryReturning(source("ARC2 staging copy"), exact);

        UUID chosen = runner(sources, URL).ensureSourceId();

        assertEquals(exact.getId(), chosen);
        verify(sources, never()).save(any());
    }

    @Test
    void usesTheOnlyPartialMatchWhenNoExactNameExists() {
        MigrationSource only = source("ARC2 staging");
        MigrationSourceRepository sources = repositoryReturning(only);

        assertEquals(only.getId(), runner(sources, URL).ensureSourceId());
        verify(sources, never()).save(any());
    }

    @Test
    void refusesToChooseBetweenSeveralSourcesContainingTheName() {
        MigrationSourceRepository sources =
                repositoryReturning(source("ARC2 staging"), source("ARC2 production"));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> runner(sources, URL).ensureSourceId());

        // The message has to name the candidates, or the operator cannot tell which to keep.
        assertTrue(thrown.getMessage().contains("ARC2 staging"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("ARC2 production"), thrown.getMessage());
        // Neither picking one nor inventing a new source: both hide the problem.
        verify(sources, never()).save(any());
    }

    @Test
    void refusesToChooseBetweenTwoSourcesWithTheSameExactName() {
        MigrationSourceRepository sources =
                repositoryReturning(source("arc2"), source("ARC2"));

        assertThrows(IllegalStateException.class, () -> runner(sources, URL).ensureSourceId());
        verify(sources, never()).save(any());
    }

    // ------------------------------------------------------- the name on a created source

    @Test
    void stampsTheCreatedSourceWithTheDatabaseTheRunWillActuallyRead() {
        MigrationSourceRepository sources = repositoryReturning();
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        runner(sources, URL).ensureSourceId();

        ArgumentCaptor<MigrationSource> saved = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(saved.capture());
        // The old code wrote "legacy_staging" here, which contradicts legacy.datasource.url. The
        // record carries no host, so hasStoredConnection() is false and the run reads that URL -- the
        // name on the record has to agree with it or the sources screen shows a database that is
        // never opened.
        assertEquals("ARC2", saved.getValue().getDatabaseName());
    }

    @Test
    void ignoresQueryParametersWhenReadingTheDatabaseFromTheUrl() {
        MigrationSourceRepository sources = repositoryReturning();
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        runner(sources, "jdbc:postgresql://db.internal:5433/ARC2?sslmode=require").ensureSourceId();

        ArgumentCaptor<MigrationSource> saved = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(saved.capture());
        assertEquals("ARC2", saved.getValue().getDatabaseName());
    }

    @Test
    void createsTheSourceWithoutADatabaseWhenTheUrlCarriesNone() {
        MigrationSourceRepository sources = repositoryReturning();
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        runner(sources, "jdbc:postgresql://localhost:5433/").ensureSourceId();

        ArgumentCaptor<MigrationSource> saved = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(saved.capture());
        // Null rather than an invented name. An invented name is the bug being fixed.
        assertNull(saved.getValue().getDatabaseName());
    }

    @Test
    void createsTheSourceWithoutADatabaseWhenTheUrlIsNotAJdbcUrl() {
        MigrationSourceRepository sources = repositoryReturning();
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        runner(sources, "not-a-url").ensureSourceId();

        ArgumentCaptor<MigrationSource> saved = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(saved.capture());
        assertNull(saved.getValue().getDatabaseName());
    }

    @Test
    void theHardcodedStagingNameIsGone() {
        MigrationSourceRepository sources = repositoryReturning();
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        runner(sources, "jdbc:postgresql://localhost:5433/ARC2").ensureSourceId();

        ArgumentCaptor<MigrationSource> saved = ArgumentCaptor.forClass(MigrationSource.class);
        verify(sources).save(saved.capture());
        assertTrue(saved.getValue().getDescription().contains("ARC2"), saved.getValue().getDescription());
    }
}
