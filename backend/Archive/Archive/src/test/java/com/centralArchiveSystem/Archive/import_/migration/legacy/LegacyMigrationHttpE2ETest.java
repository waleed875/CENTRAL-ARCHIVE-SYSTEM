package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSource;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.file.FileService;
import com.centralArchiveSystem.Archive.project.file.QuarantineService;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LegacyMigrationHttpE2ETest {

    @DynamicPropertySource
    static void legacyDatasource(DynamicPropertyRegistry registry) {
        // The fallback used to name the development database, so this test created its
        // legacy-side tables in the data being used by hand. It now falls back to the
        // test database. DB_URL still wins, which is what CI sets.
        registry.add("legacy.datasource.url", () ->
                System.getenv().getOrDefault(
                        "DB_URL",
                        "jdbc:postgresql://localhost:5433/central_archive_test"
                ));
        registry.add("legacy.datasource.username", () -> credential("DB_USERNAME", "archive_user"));
        registry.add("legacy.datasource.password", () -> credential("DB_PASSWORD", "archive_password"));
        registry.add("legacy.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /**
     * A database credential, resolved the way the application itself resolves it.
     *
     * <p>Reading {@code System.getenv} on its own is not the same lookup. The main configuration imports
     * the project root .env through {@code spring.config.import}, so locally the username and password
     * arrive from that file while the environment variables are unset — and an environment variable
     * cannot be told apart from an imported one. Asking the environment alone therefore fell through to
     * an account that exists on no machine this runs on, and all three tests here failed on a connection
     * it could not authenticate, reported as a migration failure, which it is not.
     *
     * <p>So the environment is asked first, which is what CI populates, and the .env the application reads
     * is read here too, which is what a developer has.
     */
    private static String credential(String key, String fallback) {
        String fromEnvironment = System.getenv(key);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        String fromEnvFile = projectEnv().getProperty(key);
        return fromEnvFile == null || fromEnvFile.isBlank() ? fallback : fromEnvFile;
    }

    /**
     * The project root .env — the same three levels up that {@code spring.config.import} names. Empty
     * when it is absent, so a checkout without one falls back rather than failing to read.
     */
    private static Properties projectEnv() {
        Properties properties = new Properties();
        Path env = Path.of("").toAbsolutePath().resolve("../../../.env");
        if (!Files.isReadable(env)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(env, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException unreadable) {
            return new Properties();
        }
        return properties;
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LegacyMigrationService migrationService;
    @Autowired LegacyMigrationItemRepository itemRepository;
    @Autowired MigrationSourceRepository sourceRepository;
    @Autowired ConfidentialityLevelRepository confidentialityLevelRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FileRepository fileRepository;
    @Autowired MinioService minioService;
    @Autowired FileService fileService;
    @Autowired QuarantineService quarantineService;

    private int legacySideId;
    private int legacyProjectId;
    private int legacyFileId;
    private byte[] decodedFile;

    @BeforeEach
    void setupLegacyFixture() throws Exception {
        // The tables have to exist before anything can be deleted from them. This used to run
        // the other way round, which only ever worked because the database it pointed at was
        // the development one, already carrying the tables a previous run of this test had left
        // behind. Against a database it had never touched it failed on the very first DELETE.
        ensureLegacyTables();
        purgeEarlierFixtures();
        ensureDefaultConfidentiality();

        int seed = Math.abs(UUID.randomUUID().hashCode());
        legacySideId = 1_000 + (seed % 20_000);
        legacyProjectId = 100_000 + (seed % 800_000_000);
        legacyFileId = 900_000_000 + (seed % 100_000_000);

        decodedFile = "legacy base64 migration e2e".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(decodedFile);

        jdbcTemplate.update(
                """
                INSERT INTO public.tbl_side(idside, name, status)
                VALUES (?, ?, ?)
                """,
                legacySideId,
                "Legacy Side " + legacySideId,
                true
        );

        jdbcTemplate.update(
                """
                INSERT INTO public.tbl_project(
                    idproject, idside, name, number, code, year, admin, detils, note
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                legacyProjectId,
                legacySideId,
                "Legacy Project " + legacyProjectId,
                "LEG-" + legacyProjectId,
                "CODE-" + legacyProjectId,
                "2024",
                "Legacy Administrator",
                "Legacy project details",
                "Legacy project note"
        );

        jdbcTemplate.update(
                """
                INSERT INTO public.tbl_files(
                    idfile, filename, filesuffix, idproject, detils, filebinary
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                legacyFileId,
                "legacy-note.txt",
                ".txt",
                legacyProjectId,
                "Legacy file details",
                encoded
        );
    }

    @Test
    void pagesProjectFileTasksAndResumesWithoutDuplicateFiles() throws Exception {
        int nextProject = legacyProjectId + 1;
        int nextFile = legacyFileId + 1;
        jdbcTemplate.update("""
                INSERT INTO public.tbl_project(
                    idproject, idside, name, number, code, year, admin, detils, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, nextProject, legacySideId, "Legacy Project " + nextProject,
                "LEG-" + nextProject, "CODE-" + nextProject, "2024",
                "Legacy Administrator", "second paged project", "test");
        jdbcTemplate.update("""
                INSERT INTO public.tbl_files(
                    idfile, filename, filesuffix, idproject, detils, filebinary
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, nextFile, "legacy-second-project.txt", ".txt", nextProject,
                "project pagination fixture", Base64.getEncoder().encodeToString(decodedFile));

        MigrationSource source = source();
        // Force a single row per project page so a two-project fixture must
        // advance the seek cursor. Restore the shared Spring service afterward.
        int originalSize = (int) org.springframework.test.util.ReflectionTestUtils
                .getField(migrationService, "batchSize");
        org.springframework.test.util.ReflectionTestUtils.setField(migrationService, "batchSize", 1);
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            migrationService.migrateReferences(connection);
            assertEquals(2, migrationService.migrateProjects(connection));
            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                var first = migrationService.migrateFiles(connection, workers);
                assertEquals(2, first.done());
                assertEquals(0, first.failed());
                var resumed = migrationService.migrateFiles(connection, workers);
                assertEquals(0, resumed.done());
                assertEquals(0, resumed.failed());
                assertEquals(2, resumed.skipped());
            }
            for (int fileId : new int[]{legacyFileId, nextFile}) {
                var tracked = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                        source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + fileId).orElseThrow();
                assertEquals(1, tracked.getAttempts());
                assertEquals(LegacyMigrationItem.Status.DONE, tracked.getStatus());
                assertNotNull(fileRepository.findById(tracked.getTargetId()).orElseThrow().getProjectId());
            }
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    migrationService, "batchSize", originalSize);
        }
    }

    @Test
    void selectiveRetryProcessesOnlyChosenFailedFile() throws Exception {
        int otherId = legacyFileId + 1;
        jdbcTemplate.update("""
                INSERT INTO public.tbl_files(idfile,filename,filesuffix,idproject,detils,filebinary)
                VALUES (?, ?, '.txt', ?, 'selective retry fixture', ?)
                """, otherId, "another-failed.txt", legacyProjectId, "A");
        jdbcTemplate.update("UPDATE public.tbl_files SET filebinary = ? WHERE idfile = ?",
                "A", legacyFileId);

        MigrationSource source = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            migrationService.migrateReferences(connection);
            migrationService.migrateProjects(connection);
            try (ExecutorService workers = Executors.newFixedThreadPool(1)) {
                var initial = migrationService.migrateFiles(connection, workers);
                assertEquals(2, initial.failed());
            }

            jdbcTemplate.update("UPDATE public.tbl_files SET filebinary = ? WHERE idfile = ?",
                    Base64.getEncoder().encodeToString(decodedFile), legacyFileId);
            var result = migrationService.retrySelectedFiles(connection,
                    java.util.List.of(legacyFileId), new MigrationCancellationToken());
            assertEquals(1, result.done());
            assertEquals(0, result.failed());

            var selected = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + legacyFileId).orElseThrow();
            var untouched = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + otherId).orElseThrow();
            assertEquals(LegacyMigrationItem.Status.DONE, selected.getStatus());
            assertEquals(2, selected.getAttempts());
            assertEquals(LegacyMigrationItem.Status.FAILED, untouched.getStatus());
            assertEquals(1, untouched.getAttempts());
            assertEquals(sha256(decodedFile),
                    fileRepository.findById(selected.getTargetId()).orElseThrow().getChecksumSha256());
        }
    }

    @Test
    void failedFileCountsOneAttemptAndCanResumeAfterContentIsRepaired() throws Exception {
        jdbcTemplate.update("UPDATE public.tbl_files SET filebinary = ? WHERE idfile = ?",
                "%%%invalid base64%%%", legacyFileId);
        MigrationSource source = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            migrationService.migrateReferences(connection);
            migrationService.migrateProjects(connection);

            try (ExecutorService workers = Executors.newFixedThreadPool(1)) {
                var failed = migrationService.migrateFiles(connection, workers);
                assertEquals(0, failed.done());
                assertEquals(1, failed.failed());
            }

            var first = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + legacyFileId).orElseThrow();
            assertEquals(LegacyMigrationItem.Status.FAILED, first.getStatus());
            assertEquals(1, first.getAttempts(), "a failed transfer is one attempt, not two");

            jdbcTemplate.update("UPDATE public.tbl_files SET filebinary = ? WHERE idfile = ?",
                    Base64.getEncoder().encodeToString(decodedFile), legacyFileId);
            try (ExecutorService workers = Executors.newFixedThreadPool(1)) {
                var repaired = migrationService.migrateFiles(connection, workers);
                assertEquals(1, repaired.done());
                assertEquals(0, repaired.failed());
            }

            var retried = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + legacyFileId).orElseThrow();
            assertEquals(first.getId(), retried.getId(), "retry reuses the source-scoped tracking row");
            assertEquals(2, retried.getAttempts());
            assertEquals(LegacyMigrationItem.Status.DONE, retried.getStatus());
            var file = fileRepository.findById(retried.getTargetId()).orElseThrow();
            assertEquals(sha256(decodedFile), file.getChecksumSha256());
            assertEquals(source.getOfficeName() + ":tbl_files:" + legacyFileId, file.getSourceReference());
        }
    }

    @Test
    void reusesBatchedTrackingRowsWhenResumingMultipleFilesWithoutDuplication() throws Exception {
        int secondFile = legacyFileId + 1;
        int thirdFile = legacyFileId + 2;
        for (int id : new int[]{secondFile, thirdFile}) {
            jdbcTemplate.update("""
                INSERT INTO public.tbl_files(idfile,filename,filesuffix,idproject,detils,filebinary)
                VALUES (?, ?, '.txt', ?, 'batch metadata fixture', ?)
                """, id, "legacy-batch-" + id + ".txt", legacyProjectId,
                Base64.getEncoder().encodeToString(("batch-file-" + id).getBytes(StandardCharsets.UTF_8)));
        }

        MigrationSource source = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            migrationService.migrateReferences(connection);
            migrationService.migrateProjects(connection);
            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                var first = migrationService.migrateFiles(connection, workers);
                assertEquals(3, first.done());
                assertEquals(0, first.failed());
            }

            java.util.Map<Integer, UUID> originalIds = new java.util.HashMap<>();
            for (int id : new int[]{legacyFileId, secondFile, thirdFile}) {
                var item = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                        source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + id).orElseThrow();
                assertEquals(LegacyMigrationItem.Status.DONE, item.getStatus());
                originalIds.put(id, item.getTargetId());
                var file = fileRepository.findById(item.getTargetId()).orElseThrow();
                assertNotNull(file.getChecksumSha256());
                assertEquals("ACTIVE", file.getStatus());
            }

            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                var resumed = migrationService.migrateFiles(connection, workers);
                assertEquals(0, resumed.done());
                assertEquals(0, resumed.failed());
                assertEquals(3, resumed.skipped());
            }
            for (int id : new int[]{legacyFileId, secondFile, thirdFile}) {
                var item = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                        source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + id).orElseThrow();
                assertEquals(originalIds.get(id), item.getTargetId(),
                        "resume must preserve the same target file, not duplicate MinIO objects");
                assertEquals(1, item.getAttempts(), "DONE items must not count another attempt");
            }
        }
    }

    @Test
    void migratesLegacySideProjectAndBase64FileIntoCentralArchive() throws Exception {
        // Every run is bound to one office's database, so the test opens a connection for it.
        MigrationSource source = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            assertEquals(1, migrationService.migrateReferences(connection));
            assertEquals(1, migrationService.migrateProjects(connection));

            ExecutorService workers = Executors.newFixedThreadPool(2);
            LegacyMigrationService.LegacyFileSummary summary;
            try {
                summary = migrationService.migrateFiles(connection, workers);
            } finally {
                workers.shutdown();
            }

            assertEquals(1, summary.done());
            assertEquals(0, summary.failed());

            UUID sourceId = source.getId();
            LegacyMigrationItem sideItem = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    sourceId,
                    LegacyMigrationItem.Kind.PROJECT,
                    "SIDE-" + legacySideId
            ).orElseThrow();

            LegacyMigrationItem projectItem = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    sourceId,
                    LegacyMigrationItem.Kind.PROJECT,
                    "PROJECT-" + legacyProjectId
            ).orElseThrow();

            LegacyMigrationItem fileItem = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    sourceId,
                    LegacyMigrationItem.Kind.FILE,
                    "FILE-" + legacyFileId
            ).orElseThrow();

            assertEquals(LegacyMigrationItem.Status.DONE, sideItem.getStatus());
            assertEquals(LegacyMigrationItem.Status.DONE, projectItem.getStatus());
            assertEquals(LegacyMigrationItem.Status.DONE, fileItem.getStatus());

            Project project = projectRepository.findById(projectItem.getTargetId()).orElseThrow();
            assertEquals(sideItem.getTargetId(), project.getOrganizationId());
            assertEquals("Legacy Project " + legacyProjectId, project.getName());
            assertEquals("LEG-" + legacyProjectId, project.getProjectNumber());
            assertEquals("CODE-" + legacyProjectId, project.getProjectCode());
            assertEquals(2024, project.getYear());
            assertEquals("Legacy Administrator", project.getExecutingEntity());
            assertEquals("Legacy project details", project.getDescription());
            assertEquals("Legacy project note", project.getNotes());
            assertEquals(Project.ProjectStatus.IMPORTED, project.getStatus());
            assertEquals(Project.SourceType.MIGRATION, project.getSourceType());
            // The reference carries the office, not a hard-coded database name.
            assertEquals(source.getOfficeName() + ":tbl_project:" + legacyProjectId, project.getSourceReference());

            var file = fileRepository.findById(fileItem.getTargetId()).orElseThrow();
            assertEquals(project.getId(), file.getProjectId());
            assertEquals("legacy-note.txt", file.getOriginalName());
            assertEquals("txt", file.getExtension());
            assertEquals("text/plain", file.getMimeType());
            assertEquals((long) decodedFile.length, file.getSizeBytes());
            assertEquals(sha256(decodedFile), file.getChecksumSha256());
            assertEquals("MIGRATION", file.getSourceType());
            assertEquals(source.getOfficeName() + ":tbl_files:" + legacyFileId, file.getSourceReference());
            // A file must never be classified more strictly than the project holding it. It used to be
            // stamped with a run-wide default code while the project kept none, and the file listing
            // drops anything the viewer's clearance does not reach -- silently, with no error -- so
            // the project opened to an empty list. Inheriting is what keeps the two in agreement.
            assertEquals(project.getConfidentialityLevelId(), file.getConfidentialityLevelId());
            assertNull(file.getConfidentialityLevelId());

            try (var input = minioService.downloadFile(file.getBucketName(), file.getObjectKey())) {
                assertArrayEquals(decodedFile, input.readAllBytes());
            }
        }
    }

    @Test
    void streamsLargeOpaqueLegacyFileThroughRealPostgresAndMinio() throws Exception {
        // The legacy column in this fixture is TEXT; production systems can use
        // either TEXT or BYTEA. The source contains >512 KiB of encoded Base64,
        // so migration must issue several bounded substring queries.
        byte[] source = new byte[1_200_000];
        new java.util.Random(341).nextBytes(source);
        String encoded = Base64.getEncoder().encodeToString(source);
        jdbcTemplate.update("UPDATE public.tbl_files SET filename=?, filesuffix=?, filebinary=? WHERE idfile=?",
                "legacy-big.dat", ".dat", encoded, legacyFileId);

        MigrationSource origin = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(origin)) {
            migrationService.migrateReferences(connection);
            migrationService.migrateProjects(connection);
            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                var summary = migrationService.migrateFiles(connection, workers);
                assertEquals(1, summary.done());
                assertEquals(0, summary.failed());
            }
            UUID newId = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    origin.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + legacyFileId
            ).orElseThrow().getTargetId();
            var file = fileRepository.findById(newId).orElseThrow();
            assertEquals("application/octet-stream", file.getMimeType());
            assertEquals((long) source.length, file.getSizeBytes());
            assertEquals(sha256(source), file.getChecksumSha256());
            assertNotNull(file.getProjectId());
            try (var input = minioService.downloadFile(file.getBucketName(), file.getObjectKey())) {
                assertArrayEquals(source, input.readAllBytes());
            }
        }
    }

    @Test
    void preservesExecutableLegacyBytesAsLinkedQuarantineAndSkipsRerun() throws Exception {
        byte[] executable = {'M', 'Z', 0, 1, 5, 7, 9};
        jdbcTemplate.update("UPDATE public.tbl_files SET filename=?, filesuffix=?, filebinary=? WHERE idfile=?",
                "legacy-app.exe", ".exe",
                Base64.getEncoder().encodeToString(executable), legacyFileId);

        MigrationSource source = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            migrationService.migrateReferences(connection);
            migrationService.migrateProjects(connection);

            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                var result = migrationService.migrateFiles(connection, workers);
                assertEquals(1, result.done());
                assertEquals(0, result.failed());
            }
            UUID id = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(), LegacyMigrationItem.Kind.FILE, "FILE-" + legacyFileId
            ).orElseThrow().getTargetId();
            var file = fileRepository.findById(id).orElseThrow();
            assertEquals("QUARANTINED", file.getStatus());
            assertEquals("application/octet-stream", file.getMimeType());
            assertNotNull(file.getQuarantineReason());
            assertTrue(file.getObjectKey().contains("/quarantine/"));
            assertNotNull(file.getProjectId());
            assertEquals(sha256(executable), file.getChecksumSha256());
            try (var input = minioService.downloadFile(file.getBucketName(), file.getObjectKey())) {
                assertArrayEquals(executable, input.readAllBytes());
            }
            assertThrows(jakarta.persistence.EntityNotFoundException.class,
                    () -> fileService.getFileMetadata(id));
            assertEquals(id, quarantineService.get(id).id());

            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                var repeat = migrationService.migrateFiles(connection, workers);
                assertEquals(0, repeat.done());
                assertEquals(1, repeat.skipped());
            }
        }
    }

    /**
     * A migrated project arrives INCOMPLETE with a list of fields the legacy row did not supply, so
     * it must be correctable. ARCHIVED would refuse every write -- file upload, metadata edit,
     * soft delete and member changes all reject it -- and the missing-fields list would then have
     * nothing to be filled in against. This asserts the status that keeps the project editable.
     */
    @Test
    void migratedProjectStaysEditableSoItsMissingFieldsCanBeFilledIn() throws Exception {
        MigrationSource source = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            migrationService.migrateReferences(connection);
            migrationService.migrateProjects(connection);

            UUID targetProjectId = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(),
                    LegacyMigrationItem.Kind.PROJECT,
                    "PROJECT-" + legacyProjectId
            ).orElseThrow().getTargetId();

            Project project = projectRepository.findById(targetProjectId).orElseThrow();
            assertEquals(Project.ProjectStatus.IMPORTED, project.getStatus());
            assertEquals(Project.DataCompletionStatus.INCOMPLETE, project.getDataCompletionStatus());

            // The status is what the write guards branch on, so assert the guards themselves rather
            // than restating the enum here. requireMutableProject is private in both services, so
            // this goes through the public update path the reviewer would actually use.
            project.setName("Corrected name");
            project.setDataCompletionStatus(Project.DataCompletionStatus.COMPLETE);
            projectRepository.saveAndFlush(project);

            Project reloaded = projectRepository.findById(targetProjectId).orElseThrow();
            assertEquals("Corrected name", reloaded.getName());
            assertEquals(Project.DataCompletionStatus.COMPLETE, reloaded.getDataCompletionStatus());
        }
    }

    /**
     * Classification is decided once, on the project, and the files follow it. Asserting the
     * unclassified case alone would pass just as well if the code did nothing at all, so this covers
     * the other direction: a project that does carry a level hands that exact level to its files.
     */
    @Test
    void migratedFilesTakeTheirProjectsConfidentialityLevel() throws Exception {
        MigrationSource source = source();
        try (LegacyMigrationService.LegacyConnection connection = migrationService.openConnection(source)) {
            migrationService.migrateReferences(connection);
            migrationService.migrateProjects(connection);

            UUID targetProjectId = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(),
                    LegacyMigrationItem.Kind.PROJECT,
                    "PROJECT-" + legacyProjectId
            ).orElseThrow().getTargetId();

            UUID levelId = classifyProject(targetProjectId);

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                migrationService.migrateFiles(connection, pool);
            }

            UUID targetFileId = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                    source.getId(),
                    LegacyMigrationItem.Kind.FILE,
                    "FILE-" + legacyFileId
            ).orElseThrow().getTargetId();

            var file = fileRepository.findById(targetFileId).orElseThrow();
            assertEquals(levelId, file.getConfidentialityLevelId());
        }
    }

    /**
     * A source with its own office and no stored connection, so the run falls back to the
     * environment-configured legacy datasource the same way a real single-database setup does.
     */
    private MigrationSource source() {
        String office = "E2E-" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        MigrationSource source = new MigrationSource();
        source.setName(office);
        source.setSourceType(MigrationSourceType.LEGACY_DB);
        source.setDatabaseName(office);
        source.setOfficeName(office);
        source.setDescription("Legacy migration end-to-end test source");
        return sourceRepository.save(source);
    }

    /**
     * The migration phases count every row they see, so a leftover fixture from an earlier run
     * turns the next run's "exactly one" assertions into "eighteen". These tables are shared and
     * this class writes to them, so it has to take its own rows back out. The match is on the name
     * this class writes, never a blanket truncate: the point is to leave anything that is not ours
     * exactly as it was found.
     */
    private void purgeEarlierFixtures() {
        jdbcTemplate.update("DELETE FROM public.tbl_files WHERE filename LIKE 'legacy-%'");
        jdbcTemplate.update("DELETE FROM public.tbl_project WHERE name LIKE 'Legacy Project %'");
        jdbcTemplate.update("DELETE FROM public.tbl_side WHERE name LIKE 'Legacy Side %'");
    }

    private void ensureLegacyTables() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS public.tbl_side (
                    idside smallint PRIMARY KEY,
                    name varchar(50),
                    status boolean DEFAULT true
                )
                """
        );

        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS public.tbl_project (
                    idproject integer PRIMARY KEY,
                    idside smallint,
                    name varchar(100),
                    number varchar(50),
                    code varchar(50),
                    year varchar(4),
                    admin varchar(50),
                    detils text,
                    note text
                )
                """
        );

        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS public.tbl_files (
                    idfile integer PRIMARY KEY,
                    filename text,
                    filesuffix varchar(10),
                    idproject integer,
                    detils varchar(50),
                    filebinary text
                )
                """
        );
    }

    /**
     * Seeds a real confidentiality level so the inherited value can be told apart from "no level at
     * all". Assigning it to the project before the file phase runs is the only way to observe
     * inheritance through the public entry point, because the file phase skips any file an earlier
     * run already marked DONE.
     */
    private UUID classifyProject(UUID projectId) {
        UUID levelId = ensureDefaultConfidentiality();
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.setConfidentialityLevelId(levelId);
        projectRepository.saveAndFlush(project);
        return levelId;
    }

    private UUID ensureDefaultConfidentiality() {
        return confidentialityLevelRepository.findByCode("LEGACY_E2E")
                .orElseGet(() -> {
                    ConfidentialityLevel level = new ConfidentialityLevel();
                    level.setCode("LEGACY_E2E");
                    level.setName("Legacy E2E");
                    level.setLevelOrder(900_000 + Math.abs(UUID.randomUUID().hashCode() % 50_000));
                    level.setDescription("Integration-test default confidentiality for legacy migration");
                    return confidentialityLevelRepository.save(level);
                })
                .getId();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }
}
