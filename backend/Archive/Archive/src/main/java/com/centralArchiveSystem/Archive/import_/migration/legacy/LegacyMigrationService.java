package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSource;
import com.centralArchiveSystem.Archive.import_.migration.setting.MigrationSettingsService;
import com.centralArchiveSystem.Archive.import_.migration.setting.SettingsCrypto;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.project.file.FileContentInspector;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileType;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Year;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Migrates data from a legacy office database into the new archive system.
 *
 * <p>The legacy system is installed once per organisation and each installation owns its own
 * database, all sharing the same ERD. A run is therefore always bound to a single
 * {@link LegacyConnection}, which carries that source's identity into every row it produces.
 *
 * Phases (run in order):
 *   1. references  -> tbl_side            -> core.organizations
 *   2. projects    -> tbl_project         -> project.projects
 *   3. files       -> tbl_files           -> MinIO + project.files
 *
 * Every item is tracked in import.legacy_migration_items, scoped by source, so the process is
 * resumable and two organisations never shadow each other.
 */
@Service
public class LegacyMigrationService {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationService.class);

    private static final String LEGACY_DB_SCHEMA = "public";
    private static final String LEGACY_CATEGORY_CODE = "LEGACY";
    private static final String LEGACY_TYPE_CODE = "LEGACY-FILE";

    private final Environment env;
    private final MinioService minioService;
    private final MinioConfig minioConfig;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final FileRepository fileRepository;
    private final FileCategoryRepository fileCategoryRepository;
    private final FileTypeRepository fileTypeRepository;
    private final LegacyMigrationItemRepository itemRepository;
    private final TransactionTemplate transactionTemplate;
    private final SettingsCrypto settingsCrypto;

    /**
     * Sentinel stored when the legacy row has no usable year. 1000 is below the 1900 floor that
     * {@link #parseYearSanitized} accepts, so a real year can never collide with it and the
     * incomplete-data report can treat it as "year still missing".
     */
    public static final int UNKNOWN_YEAR = 1000;

    private static final int MIN_PLAUSIBLE_YEAR = 1900;
    private static final int STALE_RUNNING_ITEM_MINUTES = 30;

    @Value("${legacy.migration.default-year:1000}")
    private int defaultYear;

    @Value("${legacy.migration.batch-size:200}")
    private int batchSize;

    @Value("${legacy.migration.minio-prefix:legacy/migration}")
    private String minioPrefix;

    private final MigrationSettingsService migrationSettingsService;

    public LegacyMigrationService(
            Environment env,
            MinioService minioService,
            MinioConfig minioConfig,
            OrganizationRepository organizationRepository,
            ProjectRepository projectRepository,
            FileRepository fileRepository,
            FileCategoryRepository fileCategoryRepository,
            FileTypeRepository fileTypeRepository,
            LegacyMigrationItemRepository itemRepository,
            MigrationSettingsService migrationSettingsService,
            SettingsCrypto settingsCrypto,
            PlatformTransactionManager transactionManager) {
        this.env = env;
        this.minioService = minioService;
        this.minioConfig = minioConfig;
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileTypeRepository = fileTypeRepository;
        this.itemRepository = itemRepository;
        this.migrationSettingsService = migrationSettingsService;
        this.settingsCrypto = settingsCrypto;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Everything a single migration run needs to read one organisation's database.
     *
     * <p>The legacy system is installed once per organisation and each installation owns a separate
     * database, so a run is always bound to exactly one source. Legacy primary keys restart from a
     * low number in every database, which means {@code idproject = 23} exists in many of them. The
     * source id and office are therefore part of every identity this run produces, otherwise the
     * second organisation migrated would be discarded as "already migrated".
     */
    public static final class LegacyConnection implements AutoCloseable {
        private final UUID sourceId;
        private final String office;
        private final String referencePrefix;
        private final JdbcTemplate jdbc;
        private final HikariDataSource dataSource;

        LegacyConnection(UUID sourceId,
                         String office,
                         String referencePrefix,
                         JdbcTemplate jdbc,
                         HikariDataSource dataSource) {
            this.sourceId = sourceId;
            this.office = office;
            this.referencePrefix = referencePrefix;
            this.jdbc = jdbc;
            this.dataSource = dataSource;
        }

        UUID sourceId() { return sourceId; }
        String office() { return office; }
        JdbcTemplate jdbc() { return jdbc; }
        String projectReference(int legacyId) { return referencePrefix + ":tbl_project:" + legacyId; }
        String fileReference(int legacyId) { return referencePrefix + ":tbl_files:" + legacyId; }

        @Override
        public void close() {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        }
    }

    /**
     * Opens a read-only pool for the given source. The pool is intentionally NOT a Spring bean so
     * it never interferes with the app's primary DataSource or the JPA auto-configuration, and it is
     * closed by the caller when the batch finishes.
     *
     * <p>A source without a stored connection falls back to the environment-configured datasource,
     * which keeps a single-database installation working with no extra configuration.
     */
    public LegacyConnection openConnection(MigrationSource source) {
        String office = source.getOfficeName();
        if (office == null || office.isBlank()) {
            throw new IllegalStateException(
                    "Migration source " + source.getId() + " has no office name, so its rows cannot be "
                            + "distinguished from another organisation's. Set an office name on the source.");
        }

        String jdbcUrl;
        String username;
        String password;

        if (source.hasStoredConnection()) {
            String host = source.getHost();
            String database = source.getDatabaseName();
            jdbcUrl = "jdbc:postgresql://" + host + ":" + source.getPort() + "/" + database;
            username = source.getUsername();
            password = settingsCrypto.decrypt(source.getPasswordEncrypted());
        } else {
            jdbcUrl = env.getProperty("legacy.datasource.url");
            username = env.getProperty("legacy.datasource.username");
            password = env.getProperty("legacy.datasource.password");
        }

        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("legacy-pool-" + office);
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(env.getProperty("legacy.datasource.driver-class-name"));
        ds.setInitializationFailTimeout(-1);
        ds.setReadOnly(true);
        ds.setAutoCommit(false);
        ds.setMaximumPoolSize(8);

        // A dedicated schema prefix is not configurable because every legacy database shares the
        // same ERD, but the reference prefix is the office, so a migrated row can always be traced
        // back to the organisation it came from even if two offices share a database name.
        String referencePrefix = office;

        return new LegacyConnection(
                source.getId(), office, referencePrefix, new JdbcTemplate(ds), ds);
    }

    // ------------------------------------------------------------------
    // Phase 1: tbl_side -> core.organizations
    // ------------------------------------------------------------------
    @Transactional
    public int migrateReferences(LegacyConnection connection) {
        List<Map<String, Object>> rows = connection.jdbc().queryForList(
                "SELECT idside, name, status FROM " + LEGACY_DB_SCHEMA + ".tbl_side ORDER BY idside");

        int created = 0;
        for (Map<String, Object> row : rows) {
            int oldId = ((Number) row.get("idside")).intValue();
            String name = (String) row.get("name");
            Boolean active = row.get("status") == null || Boolean.TRUE.equals(row.get("status"));

            // Scoped by office: two organisations both have tbl_side id 1, and they must become two
            // distinct organizations rather than one winning and the other being skipped.
            String code = orgCode(connection, oldId);
            String sourceKey = "SIDE-" + oldId;
            LegacyMigrationItem previousProjectItem = itemRepository
                    .findByMigrationSourceIdAndKindAndSourceKey(
                            connection.sourceId(), LegacyMigrationItem.Kind.PROJECT, sourceKey)
                    .orElse(null);
            if (previousProjectItem != null
                    && previousProjectItem.getStatus() == LegacyMigrationItem.Status.DONE
                    && previousProjectItem.getTargetId() != null) {
                continue;
            }

            // The organization can already exist from an earlier run whose tracking row was lost.
            // Re-link it instead of leaving a gap, otherwise the project phase would build a second
            // fallback organization for the same tbl_side id.
            UUID existingOrg = organizationRepository.findByCode(code).map(Organization::getId).orElse(null);
            if (existingOrg != null) {
                itemRepository.save(trackingItem(
                        connection, LegacyMigrationItem.Kind.PROJECT, sourceKey, (long) oldId, existingOrg));
                continue;
            }

            Organization org = new Organization();
            org.setName(name);
            org.setCode(code);
            org.setType(Organization.OrganizationType.FACTORY);
            org.setDescription("Migrated from legacy system (" + connection.office()
                    + "), tbl_side id=" + oldId);
            org.setIsActive(active);
            Organization saved = organizationRepository.save(org);
            itemRepository.save(trackingItem(
                    connection, LegacyMigrationItem.Kind.PROJECT, sourceKey, (long) oldId, saved.getId()));
            created++;
        }
        log.info("Legacy references migrated for office {}: {} organizations created",
                connection.office(), created);
        return created;
    }

    // ------------------------------------------------------------------
    // Phase 2: tbl_project -> project.projects
    // ------------------------------------------------------------------
    @Transactional
    public int migrateProjects(LegacyConnection connection) throws Exception {
        List<Map<String, Object>> rows = connection.jdbc().queryForList(
                "SELECT idproject, idside, name, number, code, year, admin, detils, note FROM "
                        + LEGACY_DB_SCHEMA + ".tbl_project ORDER BY idproject");

        int created = 0;
        for (Map<String, Object> row : rows) {
            int oldId = ((Number) row.get("idproject")).intValue();
            String sourceKey = "PROJECT-" + oldId;
            // A DONE project already has a durable target mapping. FAILED/SKIPPED rows are
            // intentionally retried so previously rejected duplicate-number projects can recover.
            LegacyMigrationItem previousProjectItem = itemRepository
                    .findByMigrationSourceIdAndKindAndSourceKey(
                            connection.sourceId(), LegacyMigrationItem.Kind.PROJECT, sourceKey)
                    .orElse(null);
            if (previousProjectItem != null
                    && previousProjectItem.getStatus() == LegacyMigrationItem.Status.DONE
                    && previousProjectItem.getTargetId() != null) {
                continue;
            }
            if (previousProjectItem != null
                    && previousProjectItem.getStatus() == LegacyMigrationItem.Status.RUNNING
                    && previousProjectItem.getStartedAt() != null
                    && previousProjectItem.getStartedAt().isAfter(
                            java.time.LocalDateTime.now().minusMinutes(STALE_RUNNING_ITEM_MINUTES))) {
                log.info("Skipping project {} because another migration attempt started recently", oldId);
                continue;
            }

            int sideId = row.get("idside") == null ? 0 : ((Number) row.get("idside")).intValue();
            UUID organizationId = resolveOrganizationId(connection, sideId);

            String rawName = (String) row.get("name");
            String name = (rawName == null || rawName.isBlank()) ? "LEGACY " + oldId : rawName;
            String number = (String) row.get("number");
            String code = (String) row.get("code");
            String admin = (String) row.get("admin");
            String desc = (String) row.get("detils");
            String note = (String) row.get("note");

            Integer year = parseYearSanitized(row.get("year"));
            String originalYear = String.valueOf(row.get("year"));

            Project project = new Project();
            // The office is part of the seed so project 23 in two organisations gets two distinct
            // UUIDs. Without it the (project_uid, version_no) unique constraint would reject the
            // second one and it would be logged as "already migrated" instead of failing loudly.
            project.setProjectUid(UUID.nameUUIDFromBytes(
                    (connection.office() + "-PROJECT-" + oldId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            project.setVersionNo(1);
            project.setIsLatestVersion(true);
            project.setOrganizationId(organizationId);
            project.setProjectNumber(number != null ? number : String.valueOf(oldId));
            project.setProjectCode(code);
            project.setName(name);
            project.setYear(year);
            project.setExecutingEntity(admin);
            project.setDescription(desc);
            project.setNotes(buildNotes(note, originalYear, year));
            project.setMissingFields(describeMissingFields(row, oldId, year));
            // IMPORTED, not ARCHIVED. A migrated project is unfinished by definition -- it lands
            // with missingFields set and INCOMPLETE completion -- and ARCHIVED is the one status
            // that refuses every write: FileService.requireMutableProject, ProjectService.update
            // and ProjectMemberService.requireMutableProject all reject it. Stamping ARCHIVED here
            // therefore locked out the exact corrections the missingFields column exists to ask
            // for. Branch-package imports already use IMPORTED for the same "arrived, not yet
            // reviewed" state, and from here the project can be corrected, completed and
            // submitted for review like any other.
            project.setStatus(Project.ProjectStatus.IMPORTED);
            project.setDataCompletionStatus(Project.DataCompletionStatus.INCOMPLETE);
            project.setSourceType(Project.SourceType.MIGRATION);
            project.setSourceReference(connection.projectReference(oldId));

            // Legacy databases can legitimately contain repeated project numbers for the same
            // organization/year. Migration rows are allowed to preserve those records; a partial
            // unique index keeps the business uniqueness rule for non-migrated projects.
            
            try {
                // saveAndFlush, not save: JPA would otherwise report the constraint violation at
                // transaction commit, long after this catch block.
                Project saved = projectRepository.saveAndFlush(project);
                itemRepository.save(trackingItem(
                        connection, LegacyMigrationItem.Kind.PROJECT, sourceKey, (long) oldId, saved.getId()));
                created++;
            } catch (DuplicateKeyException dke) {
                log.error("Project {} from office {} still violated a unique constraint: {}",
                        oldId, connection.office(), dke.getMostSpecificCause().getMessage());
                throw new IllegalStateException(
                        "Project " + oldId + " of office " + connection.office()
                                + " violated a unique constraint even though the conflict was checked"
                                + " first. The legacy row is: " + dke.getMostSpecificCause().getMessage(), dke);
            }
        }
        log.info("Legacy projects migrated for office {}: {} projects created or recovered",
                connection.office(), created);
        return created;
    }

    // ------------------------------------------------------------------
    // Phase 3: tbl_files -> MinIO + project.files (parallel, resumable)
    // ------------------------------------------------------------------
    public CompletableFuture<LegacyFileSummary> migrateFilesAsync(LegacyConnection connection, int workers) {
        return CompletableFuture.supplyAsync(() -> {
            ExecutorService pool = null;
            try {
                int parallel = Math.max(1, Math.min(workers <= 0 ? 4 : workers, 8));
                pool = Executors.newFixedThreadPool(parallel);
                return migrateFiles(connection, pool);
            } finally {
                if (pool != null) pool.shutdown();
            }
        });
    }

    public LegacyFileSummary migrateFiles(LegacyConnection connection, ExecutorService pool) {
        LegacyFileSummary summary = new LegacyFileSummary();

        UUID defaultCategoryId = resolveDefaultCategoryId();
        UUID defaultTypeId = resolveDefaultTypeId(defaultCategoryId);

        List<Map<String, Object>> projects = connection.jdbc().queryForList(
                "SELECT idproject FROM " + LEGACY_DB_SCHEMA + ".tbl_project");

        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (Map<String, Object> projectRow : projects) {
            int legacyProjectId = ((Number) projectRow.get("idproject")).intValue();
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> migrateFilesForProject(
                            connection,
                            legacyProjectId,
                            summary,
                            defaultCategoryId,
                            defaultTypeId
                    ),
                    pool
            ).exceptionally(error -> {
                log.error("File migration task failed for legacy project {} in office {}",
                        legacyProjectId, connection.office(), error);
                summary.incrementFailed();
                return null;
            });
            futures.add(future);
        }

        // Each project task handles its own exceptional completion above. Joining the aggregate
        // now waits for every project and does not let one failed project hide the remaining work.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info(
                "Legacy files migrated for office {}: {} done, {} failed, {} skipped",
                connection.office(),
                summary.done(),
                summary.failed(),
                summary.skipped()
        );
        return summary;
    }

    private void migrateFilesForProject(LegacyConnection connection,
                                        int legacyProjectId,
                                        LegacyFileSummary summary,
                                        UUID defaultCategoryId,
                                        UUID defaultTypeId) {
        String projectSourceKey = "PROJECT-" + legacyProjectId;
        UUID targetProjectId = itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                        connection.sourceId(),
                        LegacyMigrationItem.Kind.PROJECT,
                        projectSourceKey
                )
                .map(LegacyMigrationItem::getTargetId)
                .orElse(null);

        if (targetProjectId == null) {
            log.warn("No target project for legacy project {} in office {}; recording its files for retry",
                    legacyProjectId, connection.office());
            List<Integer> sourceFileIds = connection.jdbc().queryForList(
                    "SELECT idfile FROM " + LEGACY_DB_SCHEMA
                            + ".tbl_files WHERE idproject = ? ORDER BY idfile",
                    Integer.class, legacyProjectId);
            for (Integer idfile : sourceFileIds) {
                String sourceKey = "FILE-" + idfile;
                LegacyMigrationItem existing = itemRepository
                        .findByMigrationSourceIdAndKindAndSourceKey(
                                connection.sourceId(), LegacyMigrationItem.Kind.FILE, sourceKey)
                        .orElse(null);
                if (existing != null
                        && existing.getStatus() == LegacyMigrationItem.Status.DONE
                        && existing.getTargetId() != null) {
                    summary.incrementSkipped();
                    continue;
                }
                LegacyMigrationItem item = trackingItem(
                        connection, LegacyMigrationItem.Kind.FILE, sourceKey, idfile.longValue(), null);
                item.setStatus(LegacyMigrationItem.Status.FAILED);
                item.setError("No target project mapping exists for legacy project " + legacyProjectId
                        + "; retry after the project is migrated.");
                item.setFinishedAt(java.time.LocalDateTime.now());
                itemRepository.save(item);
                summary.incrementFailed();
            }
            return;
        }

        // Read here rather than once per run, because a run spans every project it migrated and a
        // classification applied to the whole batch would stamp the last project read onto all of
        // them. A migrated project carries no level of its own, so this is null today; the lookup
        // exists so a project classified after migration -- or one stamped by a future run -- has
        // its files follow it instead of landing with a level the project never had.
        UUID projectConfidentialityId = projectRepository.findById(targetProjectId)
                .map(Project::getConfidentialityLevelId)
                .orElse(null);

        connection.jdbc().query(
                "SELECT idfile, filename, filesuffix, filebinary FROM "
                        + LEGACY_DB_SCHEMA
                        + ".tbl_files WHERE idproject = ? ORDER BY idfile",
                preparedStatement -> {
                    preparedStatement.setInt(1, legacyProjectId);
                    preparedStatement.setFetchSize(Math.max(1, batchSize));
                },
                resultSet -> {
                    int idfile = resultSet.getInt("idfile");
                    String sourceKey = "FILE-" + idfile;

                    LegacyMigrationItem previousFileItem = itemRepository
                            .findByMigrationSourceIdAndKindAndSourceKey(
                                    connection.sourceId(), LegacyMigrationItem.Kind.FILE, sourceKey)
                            .orElse(null);
                    if (previousFileItem != null
                            && previousFileItem.getStatus() == LegacyMigrationItem.Status.DONE
                            && previousFileItem.getTargetId() != null) {
                        summary.incrementSkipped();
                        return;
                    }
                    if (previousFileItem != null
                            && previousFileItem.getStatus() == LegacyMigrationItem.Status.RUNNING
                            && previousFileItem.getStartedAt() != null
                            && previousFileItem.getStartedAt().isAfter(
                                    java.time.LocalDateTime.now().minusMinutes(STALE_RUNNING_ITEM_MINUTES))) {
                        log.info("Skipping file {} because another migration attempt started recently", idfile);
                        summary.incrementSkipped();
                        return;
                    }

                    String originalName = resultSet.getString("filename");
                    String suffix = resultSet.getString("filesuffix");

                    InputStream encodedBase64 = resultSet.getBinaryStream("filebinary");
                    try {
                            migrateOneFile(
                                    connection,
                                    idfile,
                                    sourceKey,
                                    targetProjectId,
                                    originalName,
                                    suffix,
                                    encodedBase64,
                                    summary,
                                    defaultCategoryId,
                                    defaultTypeId,
                                    projectConfidentialityId
                            );
                    } finally {
                        if (encodedBase64 != null) {
                            try {
                                encodedBase64.close();
                            } catch (Exception closeError) {
                                log.warn(
                                        "Failed to close legacy file stream for id={}",
                                        idfile,
                                        closeError
                                );
                            }
                        }
                    }
                }
        );
    }

    private void migrateOneFile(LegacyConnection connection,
                                int idfile,
                                String sourceKey,
                                UUID targetProjectId,
                                String originalName,
                                String suffix,
                                InputStream encodedBase64,
                                LegacyFileSummary summary,
                                UUID defaultCategoryId,
                                UUID defaultTypeId,
                                UUID projectConfidentialityId) {
        LegacyMigrationItem item = trackingItem(
                connection,
                LegacyMigrationItem.Kind.FILE,
                sourceKey,
                (long) idfile,
                null
        );
        item.setStatus(LegacyMigrationItem.Status.RUNNING);
        item.setStartedAt(java.time.LocalDateTime.now());
        item.setFinishedAt(null);
        item.setError(null);
        item.setAttempts((item.getAttempts() == null ? 0 : item.getAttempts()) + 1);
        itemRepository.save(item);

        Path spool = null;
        String bucket = minioConfig.getBucket();
        String objectKey = null;
        boolean uploadedToObjectStorage = false;

        try {
            PreparedLegacyFile prepared = prepareLegacyFile(
                    encodedBase64,
                    originalName,
                    suffix
            );
            spool = prepared.path();

            String storedName = UUID.randomUUID() + "." + prepared.extension();
            objectKey = minioPrefix + "/" + targetProjectId + "/" + storedName;

            try (InputStream uploadInput = Files.newInputStream(spool)) {
                minioService.uploadRaw(
                        uploadInput,
                        bucket,
                        objectKey,
                        prepared.mimeType(),
                        prepared.sizeBytes()
                );
            }
            uploadedToObjectStorage = true;

            FileEntity file = new FileEntity();
            file.setProjectId(targetProjectId);
            file.setCategoryId(defaultCategoryId);
            file.setTypeId(defaultTypeId);
            // Inherited from the project, not stamped from a run-wide setting. The project itself
            // is migrated with no level, so a file that arrived carrying one was strictly more
            // restricted than its own project -- and the file listing drops anything the viewer's
            // clearance does not cover, silently, so the project opened to an empty file list with
            // no error to explain it. Inheriting keeps the two levels in agreement: a project
            // classified later is followed by its files, and an unclassified project stays visible
            // to everyone who can see the project itself.
            file.setConfidentialityLevelId(projectConfidentialityId);
            file.setOriginalName(
                    originalName != null && !originalName.isBlank()
                            ? originalName
                            : ("legacy-" + idfile + "." + prepared.extension())
            );
            file.setStoredName(storedName);
            file.setBucketName(bucket);
            file.setObjectKey(objectKey);
            file.setExtension(prepared.extension());
            file.setMimeType(prepared.mimeType());
            file.setSizeBytes(prepared.sizeBytes());
            file.setChecksumSha256(prepared.checksumSha256());
            file.setStatus("ACTIVE");
            file.setSourceType("MIGRATION");
            file.setSourceReference(connection.fileReference(idfile));

            FileEntity saved = transactionTemplate.execute(status -> {
                FileEntity persisted = fileRepository.save(file);

                item.setTargetId(persisted.getId());
                item.setStatus(LegacyMigrationItem.Status.DONE);
                item.setFinishedAt(java.time.LocalDateTime.now());
                itemRepository.save(item);

                return persisted;
            });

            if (saved == null) {
                throw new IllegalStateException(
                        "Legacy file metadata transaction returned no saved file");
            }

            summary.incrementDone();
        } catch (Exception e) {
            log.error(
                    "Failed to migrate file {} ({}): {}",
                    idfile,
                    originalName,
                    e.getMessage(),
                    e
            );

            if (uploadedToObjectStorage && objectKey != null) {
                try {
                    minioService.deleteFile(bucket, objectKey);
                } catch (Exception cleanupError) {
                    log.error(
                            "Failed to remove orphan legacy migration object: {}",
                            objectKey,
                            cleanupError
                    );
                }
            }

            item.setStatus(LegacyMigrationItem.Status.FAILED);
            item.setError(e.getMessage());
            item.setFinishedAt(java.time.LocalDateTime.now());
            item.setAttempts((item.getAttempts() == null ? 0 : item.getAttempts()) + 1);
            itemRepository.save(item);
            summary.incrementFailed();
        } finally {
            if (spool != null) {
                try {
                    Files.deleteIfExists(spool);
                } catch (Exception cleanupError) {
                    log.warn("Failed to remove legacy migration spool file: {}", spool, cleanupError);
                }
            }
        }
    }

    static PreparedLegacyFile prepareLegacyFile(InputStream encodedBase64,
                                                String originalName,
                                                String suffix) throws Exception {
        if (encodedBase64 == null) {
            throw new IllegalArgumentException("Legacy file content is missing");
        }

        String extension = normalizeLegacyExtension(suffix, originalName);
        Path spool = Files.createTempFile("legacy-archive-migration-", "." + extension);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] prefix = new byte[FileContentInspector.SNIFF_BYTES];
        int prefixLength = 0;
        long totalBytes = 0L;

        try {
            try (InputStream decoded = Base64.getMimeDecoder().wrap(encodedBase64);
                 OutputStream output = Files.newOutputStream(spool)) {
                byte[] buffer = new byte[64 * 1024];
                int read;

                while ((read = decoded.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);

                    if (prefixLength < prefix.length) {
                        int copyLength = Math.min(
                                read,
                                prefix.length - prefixLength
                        );
                        System.arraycopy(
                                buffer,
                                0,
                                prefix,
                                prefixLength,
                                copyLength
                        );
                        prefixLength += copyLength;
                    }

                    totalBytes += read;
                }
            }

            if (totalBytes == 0) {
                throw new IllegalArgumentException("Legacy file content is empty");
            }

            byte[] actualPrefix = java.util.Arrays.copyOf(prefix, prefixLength);
            String mimeType = FileContentInspector.validatePackageEntry(
                    actualPrefix,
                    extension,
                    spool
            );

            return new PreparedLegacyFile(
                    spool,
                    totalBytes,
                    toHex(digest.digest()),
                    mimeType,
                    extension
            );
        } catch (Exception e) {
            Files.deleteIfExists(spool);
            throw e;
        }
    }

    private static String normalizeLegacyExtension(String suffix, String originalName) {
        String nameExtension = FileContentInspector.extensionFromName(originalName);
        String candidate = suffix;

        if (candidate == null || candidate.isBlank()) {
            candidate = nameExtension;
        }

        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException(
                    "Legacy file has no resolvable extension: " + originalName);
        }

        String normalized = candidate.trim().toLowerCase(java.util.Locale.ROOT);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Legacy file has no resolvable extension: " + originalName);
        }

        if (nameExtension != null
                && !FileContentInspector.extensionsEquivalent(normalized, nameExtension)) {
            throw new IllegalArgumentException(
                    "Legacy file suffix does not match original filename: " + originalName);
        }

        return normalized;
    }

    private static String toHex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    record PreparedLegacyFile(
            Path path,
            long sizeBytes,
            String checksumSha256,
            String mimeType,
            String extension
    ) {}

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private LegacyMigrationItem trackingItem(LegacyConnection connection,
                                             LegacyMigrationItem.Kind kind,
                                             String sourceKey,
                                             Long sourceId,
                                             UUID targetId) {
        LegacyMigrationItem item = itemRepository
                .findByMigrationSourceIdAndKindAndSourceKey(connection.sourceId(), kind, sourceKey)
                .orElseGet(LegacyMigrationItem::new);
        item.setKind(kind);
        item.setSourceKey(sourceKey);
        // Carries the scope of the unique (source, kind, source_key) index. Reuse an existing
        // FAILED/SKIPPED/stale tracking row so retries update it instead of violating uniqueness.
        item.setMigrationSourceId(connection.sourceId());
        item.setSourceId(sourceId);
        item.setTargetId(targetId);
        item.setStatus(targetId != null ? LegacyMigrationItem.Status.DONE : LegacyMigrationItem.Status.PENDING);
        item.setError(null);
        item.setStartedAt(null);
        item.setFinishedAt(targetId != null ? java.time.LocalDateTime.now() : null);
        return item;
    }

    private UUID resolveOrganizationId(LegacyConnection connection, int sideId) {
        if (sideId <= 0) {
            // No usable tbl_side row: fall back to a per-office placeholder rather than sharing one
            // organization across every office that is missing the reference.
            String code = orgCode(connection, 0);
            return organizationRepository.findByCode(code)
                    .map(Organization::getId)
                    .orElseGet(() -> createFallbackOrganization(connection, code, sideId));
        }
        return itemRepository.findByMigrationSourceIdAndKindAndSourceKey(
                        connection.sourceId(), LegacyMigrationItem.Kind.PROJECT, "SIDE-" + sideId)
                .map(LegacyMigrationItem::getTargetId)
                .orElseGet(() -> {
                    String code = orgCode(connection, sideId);
                    UUID existing = organizationRepository.findByCode(code).map(Organization::getId).orElse(null);
                    if (existing != null) return existing;
                    UUID created = createFallbackOrganization(connection, code, sideId);
                    itemRepository.save(trackingItem(
                            connection, LegacyMigrationItem.Kind.PROJECT, "SIDE-" + sideId, (long) sideId, created));
                    return created;
                });
    }

    private UUID createFallbackOrganization(LegacyConnection connection, String code, int sideId) {
        Organization org = new Organization();
        org.setName("LEGACY SIDE " + sideId + " (" + connection.office() + ")");
        org.setCode(code);
        org.setType(Organization.OrganizationType.FACTORY);
        org.setIsActive(true);
        org.setDescription("Auto-created fallback organization for legacy side " + sideId
                + " in office " + connection.office());
        return organizationRepository.save(org).getId();
    }

    private UUID resolveDefaultCategoryId() {
        return fileCategoryRepository.findByCode(LEGACY_CATEGORY_CODE)
                .map(FileCategory::getId)
                .orElseGet(this::createDefaultCategory);
    }

    private UUID createDefaultCategory() {
        FileCategory category = new FileCategory();
        category.setCode(LEGACY_CATEGORY_CODE);
        category.setName("Legacy Migration");
        category.setDescription("Default category for files migrated from the legacy system");
        category.setIsActive(true);
        return fileCategoryRepository.save(category).getId();
    }

    private UUID resolveDefaultTypeId(UUID categoryId) {
        return fileTypeRepository.findByCode(LEGACY_TYPE_CODE)
                .map(existing -> {
                    if (!categoryId.equals(existing.getCategoryId())) {
                        throw new IllegalStateException(
                                "Existing LEGACY-FILE type is linked to an unexpected category");
                    }
                    return existing.getId();
                })
                .orElseGet(() -> createDefaultType(categoryId));
    }

    private UUID createDefaultType(UUID categoryId) {
        FileType type = new FileType();
        type.setCategoryId(categoryId);
        type.setCode(LEGACY_TYPE_CODE);
        type.setName("Legacy File");
        type.setDefaultExtension("*");
        type.setDescription("Default type for files migrated from the legacy system");
        type.setIsActive(true);
        return fileTypeRepository.save(type).getId();
    }

    private int parseYearSanitized(Object rawYear) {
        return sanitizeYear(rawYear, defaultYear);
    }

    /**
     * Years outside this window are treated as unusable. The upper bound matters: the legacy table
     * contains values like 4564 that a lower-bound-only check would happily accept, leaving records
     * that look complete while carrying an obviously wrong year.
     */
    static int sanitizeYear(Object rawYear, int unknownYear) {
        if (rawYear == null) return unknownYear;
        String s = String.valueOf(rawYear).trim();
        if (s.isEmpty()) return unknownYear;
        try {
            int y = Integer.parseInt(s);
            return y >= MIN_PLAUSIBLE_YEAR && y <= Year.now().getValue() ? y : unknownYear;
        } catch (NumberFormatException nfe) {
            return unknownYear;
        }
    }

    private String describeMissingFields(Map<String, Object> row, int oldId, int year) {
        return describeMissingFields(row, oldId, year, defaultYear);
    }

    /**
     * Names the mandatory fields that the legacy row did not supply, so the record still enters the
     * platform but the incomplete-data report can list what a reviewer has to fill in.
     */
    static String describeMissingFields(Map<String, Object> row, int oldId, int year, int unknownYear) {
        List<String> missing = new ArrayList<>();
        if (isBlank((String) row.get("name"))) {
            missing.add("name");
        }
        if (isBlank((String) row.get("number"))) {
            missing.add("projectNumber");
        }
        if (isBlank((String) row.get("code"))) {
            missing.add("projectCode");
        }
        if (year == unknownYear) {
            // unknownYear is the "still missing" marker, so any year landing on it was unusable.
            missing.add("year");
        }
        if (isBlank((String) row.get("admin"))) {
            missing.add("executingEntity");
        }
        if (isBlank((String) row.get("detils"))) {
            missing.add("description");
        }
        if (isBlankSide(row.get("idside"))) {
            missing.add("organizationId");
        }
        if (oldId <= 0) {
            missing.add("sourceReference");
        }
        return missing.isEmpty() ? null : String.join(",", missing);
    }

    private static boolean isBlankSide(Object idside) {
        return idside == null || ((Number) idside).intValue() == 0;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String buildNotes(String originalNote, String originalYear, int usedYear) {
        StringBuilder sb = new StringBuilder();
        if (originalNote != null && !originalNote.isBlank()) {
            sb.append(originalNote);
        }
        if (usedYear == defaultYear && originalYear != null && !originalYear.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Original legacy year: ").append(originalYear);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Organization codes are part of the identity of migrated data, so the office is included.
     * Every organisation's legacy database numbers {@code tbl_side} from the same low sequence, and
     * a code built from the bare id would make the second office's side look like the first one's.
     */
    private String orgCode(LegacyConnection connection, int sideId) {
        return "LEGACY-" + connection.office() + "-" + sideId;
    }

    public static class LegacyFileSummary {
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();

        public void incrementDone() {
            done.incrementAndGet();
        }

        public void incrementFailed() {
            failed.incrementAndGet();
        }

        public void incrementSkipped() {
            skipped.incrementAndGet();
        }

        public int done() {
            return done.get();
        }

        public int failed() {
            return failed.get();
        }

        public int skipped() {
            return skipped.get();
        }
    }
}
