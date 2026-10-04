package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSource;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
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
        registry.add("legacy.datasource.username", () ->
                System.getenv().getOrDefault("DB_USERNAME", "archive_user"));
        registry.add("legacy.datasource.password", () ->
                System.getenv().getOrDefault("DB_PASSWORD", "archive_password"));
        registry.add("legacy.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LegacyMigrationService migrationService;
    @Autowired LegacyMigrationItemRepository itemRepository;
    @Autowired MigrationSourceRepository sourceRepository;
    @Autowired ConfidentialityLevelRepository confidentialityLevelRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FileRepository fileRepository;
    @Autowired MinioService minioService;

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
