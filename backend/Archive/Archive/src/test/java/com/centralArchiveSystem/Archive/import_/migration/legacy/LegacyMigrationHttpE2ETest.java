package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
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

@SpringBootTest(properties = {
        "legacy.migration.default-confidentiality-code=LEGACY_E2E"
})
class LegacyMigrationHttpE2ETest {

    @DynamicPropertySource
    static void legacyDatasource(DynamicPropertyRegistry registry) {
        registry.add("legacy.datasource.url", () ->
                System.getenv().getOrDefault(
                        "DB_URL",
                        "jdbc:postgresql://localhost:5433/central_archive"
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
        ensureLegacyTables();
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
        assertEquals(1, migrationService.migrateReferences());
        assertEquals(1, migrationService.migrateProjects());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        LegacyMigrationService.LegacyFileSummary summary;
        try {
            summary = migrationService.migrateFiles(workers);
        } finally {
            workers.shutdown();
        }

        assertEquals(1, summary.done());
        assertEquals(0, summary.failed());

        LegacyMigrationItem sideItem = itemRepository.findByKindAndSourceKey(
                LegacyMigrationItem.Kind.PROJECT,
                "SIDE-" + legacySideId
        ).orElseThrow();

        LegacyMigrationItem projectItem = itemRepository.findByKindAndSourceKey(
                LegacyMigrationItem.Kind.PROJECT,
                "PROJECT-" + legacyProjectId
        ).orElseThrow();

        LegacyMigrationItem fileItem = itemRepository.findByKindAndSourceKey(
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
        assertEquals(Project.ProjectStatus.ARCHIVED, project.getStatus());
        assertEquals(Project.SourceType.MIGRATION, project.getSourceType());
        assertEquals("ARC2:tbl_project:" + legacyProjectId, project.getSourceReference());

        var file = fileRepository.findById(fileItem.getTargetId()).orElseThrow();
        assertEquals(project.getId(), file.getProjectId());
        assertEquals("legacy-note.txt", file.getOriginalName());
        assertEquals("txt", file.getExtension());
        assertEquals("text/plain", file.getMimeType());
        assertEquals((long) decodedFile.length, file.getSizeBytes());
        assertEquals(sha256(decodedFile), file.getChecksumSha256());
        assertEquals("MIGRATION", file.getSourceType());
        assertEquals("ARC2:tbl_files:" + legacyFileId, file.getSourceReference());

        try (var input = minioService.downloadFile(file.getBucketName(), file.getObjectKey())) {
            assertArrayEquals(decodedFile, input.readAllBytes());
        }
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

    private void ensureDefaultConfidentiality() {
        confidentialityLevelRepository.findByCode("LEGACY_E2E").orElseGet(() -> {
            ConfidentialityLevel level = new ConfidentialityLevel();
            level.setCode("LEGACY_E2E");
            level.setName("Legacy E2E");
            level.setLevelOrder(900_000 + Math.abs(UUID.randomUUID().hashCode() % 50_000));
            level.setDescription("Integration-test default confidentiality for legacy migration");
            return confidentialityLevelRepository.save(level);
        });
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }
}
