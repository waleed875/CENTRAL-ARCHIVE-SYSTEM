package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Migrates data from the legacy staging database (restored from the 600GB
 * pg_dump backup) into the new archive system.
 *
 * Phases (run in order):
 *   1. references  -> tbl_side            -> core.organizations
 *   2. projects    -> tbl_project         -> project.projects
 *   3. files       -> tbl_files           -> MinIO + project.files
 *
 * Every item is tracked in import.legacy_migration_items so the process is
 * resumable: on restart we continue from PENDING/RUNNING items.
 */
@Service
public class LegacyMigrationService {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationService.class);

    private static final String LEGACY_DB_SCHEMA = "public";
    private static final String DEFAULT_MIME = "application/octet-stream";

    private final JdbcTemplate legacyJdbc;
    private final MinioService minioService;
    private final MinioConfig minioConfig;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final FileRepository fileRepository;
    private final FileCategoryRepository fileCategoryRepository;
    private final FileTypeRepository fileTypeRepository;
    private final ConfidentialityLevelRepository confidentialityLevelRepository;
    private final LegacyMigrationItemRepository itemRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${legacy.migration.default-year:2000}")
    private int defaultYear;

    @Value("${legacy.migration.batch-size:200}")
    private int batchSize;

    @Value("${legacy.migration.minio-prefix:legacy/migration}")
    private String minioPrefix;

    public LegacyMigrationService(
            Environment env,
            MinioService minioService,
            MinioConfig minioConfig,
            OrganizationRepository organizationRepository,
            ProjectRepository projectRepository,
            FileRepository fileRepository,
            FileCategoryRepository fileCategoryRepository,
            FileTypeRepository fileTypeRepository,
            ConfidentialityLevelRepository confidentialityLevelRepository,
            LegacyMigrationItemRepository itemRepository,
            PlatformTransactionManager transactionManager) {
        this.legacyJdbc = buildLegacyJdbc(env);
        this.minioService = minioService;
        this.minioConfig = minioConfig;
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileTypeRepository = fileTypeRepository;
        this.confidentialityLevelRepository = confidentialityLevelRepository;
        this.itemRepository = itemRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Builds the isolated JdbcTemplate for the legacy staging database. This is
     * intentionally NOT a Spring bean so it never interferes with the app's
     * primary DataSource or Spring Data JDBC/JPA auto-configuration.
     */
    private JdbcTemplate buildLegacyJdbc(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("legacy-staging-pool");
        ds.setJdbcUrl(env.getProperty("legacy.datasource.url"));
        ds.setUsername(env.getProperty("legacy.datasource.username"));
        ds.setPassword(env.getProperty("legacy.datasource.password"));
        ds.setDriverClassName(env.getProperty("legacy.datasource.driver-class-name"));
        ds.setInitializationFailTimeout(-1);
        ds.setReadOnly(true);
        ds.setAutoCommit(false);
        ds.setMaximumPoolSize(8);
        return new JdbcTemplate(ds);
    }

    // ------------------------------------------------------------------
    // Phase 1: tbl_side -> core.organizations
    // ------------------------------------------------------------------
    @Transactional
    public int migrateReferences() {
        List<Map<String, Object>> rows = legacyJdbc.queryForList(
                "SELECT idside, name, status FROM " + LEGACY_DB_SCHEMA + ".tbl_side ORDER BY idside");

        int created = 0;
        for (Map<String, Object> row : rows) {
            int oldId = ((Number) row.get("idside")).intValue();
            String name = (String) row.get("name");
            Boolean active = row.get("status") == null || Boolean.TRUE.equals(row.get("status"));
            String sourceKey = "SIDE-" + oldId;

            if (itemRepository.existsByKindAndSourceKey(LegacyMigrationItem.Kind.PROJECT, sourceKey)) {
                // guard: use a distinct key namespace to avoid collision with projects; see below
            }
            if (organizationRepository.existsByCode(orgCode(oldId))) {
                continue;
            }

            Organization org = new Organization();
            org.setName(name);
            org.setCode(orgCode(oldId));
            org.setType(Organization.OrganizationType.FACTORY);
            org.setDescription("Migrated from legacy system (ARC2), tbl_side id=" + oldId);
            org.setIsActive(active);
            Organization saved = organizationRepository.save(org);
            itemRepository.save(trackingItem(LegacyMigrationItem.Kind.PROJECT, sourceKey, (long) oldId, saved.getId()));
            created++;
        }
        log.info("Legacy references migrated: {} organizations created", created);
        return created;
    }

    // ------------------------------------------------------------------
    // Phase 2: tbl_project -> project.projects
    // ------------------------------------------------------------------
    @Transactional
    public int migrateProjects() throws Exception {
        List<Map<String, Object>> rows = legacyJdbc.queryForList(
                "SELECT idproject, idside, name, number, code, year, admin, detils, note FROM "
                        + LEGACY_DB_SCHEMA + ".tbl_project ORDER BY idproject");

        int created = 0;
        for (Map<String, Object> row : rows) {
            int oldId = ((Number) row.get("idproject")).intValue();
            String sourceKey = "PROJECT-" + oldId;
            if (itemRepository.existsByKindAndSourceKey(LegacyMigrationItem.Kind.PROJECT, sourceKey)) {
                continue;
            }

            int sideId = row.get("idside") == null ? 0 : ((Number) row.get("idside")).intValue();
            UUID organizationId = resolveOrganizationId(sideId);

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
            project.setId(UUID.randomUUID());
            project.setProjectUid(UUID.nameUUIDFromBytes(("LEGACY-" + oldId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
            project.setStatus(Project.ProjectStatus.ARCHIVED);
            project.setDataCompletionStatus(Project.DataCompletionStatus.INCOMPLETE);
            project.setSourceType(Project.SourceType.MIGRATION);
            project.setSourceReference("ARC2:tbl_project:" + oldId);

            try {
                Project saved = projectRepository.save(project);
                itemRepository.save(trackingItem(LegacyMigrationItem.Kind.PROJECT, sourceKey, (long) oldId, saved.getId()));
                created++;
            } catch (DuplicateKeyException dke) {
                log.warn("Project {} already migrated, skipping", oldId);
            }
        }
        log.info("Legacy projects migrated: {} projects created", created);
        return created;
    }

    // ------------------------------------------------------------------
    // Phase 3: tbl_files -> MinIO + project.files (parallel, resumable)
    // ------------------------------------------------------------------
    public CompletableFuture<LegacyFileSummary> migrateFilesAsync(int workers) {
        return CompletableFuture.supplyAsync(() -> {
            ExecutorService pool = null;
            try {
                int parallel = Math.max(1, Math.min(workers <= 0 ? 4 : workers, 8));
                pool = Executors.newFixedThreadPool(parallel);
                return migrateFiles(pool);
            } finally {
                if (pool != null) pool.shutdown();
            }
        });
    }

    public LegacyFileSummary migrateFiles(ExecutorService pool) {
        LegacyFileSummary summary = new LegacyFileSummary();

        List<Map<String, Object>> projects = legacyJdbc.queryForList(
                "SELECT idproject FROM " + LEGACY_DB_SCHEMA + ".tbl_project");

        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (Map<String, Object> projectRow : projects) {
            int legacyProjectId = ((Number) projectRow.get("idproject")).intValue();
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> migrateFilesForProject(legacyProjectId, summary), pool);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Parallel file migration failed", e);
        }
        log.info(
                "Legacy files migrated: {} done, {} failed, {} skipped",
                summary.done(),
                summary.failed(),
                summary.skipped()
        );
        return summary;
    }

    private void migrateFilesForProject(int legacyProjectId, LegacyFileSummary summary) {
        String projectSourceKey = "PROJECT-" + legacyProjectId;
        UUID targetProjectId = itemRepository.findByKindAndSourceKey(
                        LegacyMigrationItem.Kind.PROJECT,
                        projectSourceKey
                )
                .map(LegacyMigrationItem::getTargetId)
                .orElse(null);

        if (targetProjectId == null) {
            log.warn("No target project for legacy project {}, skipping its files", legacyProjectId);
            return;
        }

        legacyJdbc.query(
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

                    if (itemRepository.existsByKindAndSourceKey(
                            LegacyMigrationItem.Kind.FILE,
                            sourceKey
                    )) {
                        summary.incrementSkipped();
                        return;
                    }

                    String originalName = resultSet.getString("filename");
                    String suffix = resultSet.getString("filesuffix");

                    InputStream encodedBase64 = resultSet.getBinaryStream("filebinary");
                    try {
                        migrateOneFile(
                                idfile,
                                sourceKey,
                                targetProjectId,
                                originalName,
                                suffix,
                                encodedBase64,
                                summary
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

    private void migrateOneFile(int idfile,
                                String sourceKey,
                                UUID targetProjectId,
                                String originalName,
                                String suffix,
                                InputStream encodedBase64,
                                LegacyFileSummary summary) {
        LegacyMigrationItem item = trackingItem(
                LegacyMigrationItem.Kind.FILE,
                sourceKey,
                (long) idfile,
                null
        );
        item.setStatus(LegacyMigrationItem.Status.RUNNING);
        item.setStartedAt(java.time.LocalDateTime.now());
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
            file.setCategoryId(resolveDefaultCategoryId());
            file.setTypeId(resolveDefaultTypeId());
            file.setConfidentialityLevelId(resolveDefaultConfidentialityId());
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
            file.setSourceReference("ARC2:tbl_files:" + idfile);

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
        String candidate = suffix;
        if (candidate == null || candidate.isBlank()) {
            candidate = FileContentInspector.extensionFromName(originalName);
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
    private LegacyMigrationItem trackingItem(LegacyMigrationItem.Kind kind, String sourceKey, Long sourceId, UUID targetId) {
        LegacyMigrationItem item = new LegacyMigrationItem();
        item.setKind(kind);
        item.setSourceKey(sourceKey);
        item.setSourceId(sourceId);
        item.setTargetId(targetId);
        item.setStatus(targetId != null ? LegacyMigrationItem.Status.DONE : LegacyMigrationItem.Status.PENDING);
        return item;
    }

    private UUID resolveOrganizationId(int sideId) {
        return itemRepository.findByKindAndSourceKey(LegacyMigrationItem.Kind.PROJECT, "SIDE-" + sideId)
                .map(LegacyMigrationItem::getTargetId)
                .orElseGet(() -> {
                    Organization org = new Organization();
                    org.setName("LEGACY SIDE " + sideId);
                    org.setCode(orgCode(sideId));
                    org.setType(Organization.OrganizationType.FACTORY);
                    org.setIsActive(true);
                    org.setDescription("Auto-created fallback organization for legacy side " + sideId);
                    Organization saved = organizationRepository.save(org);
                    itemRepository.save(trackingItem(LegacyMigrationItem.Kind.PROJECT, "SIDE-" + sideId, (long) sideId, saved.getId()));
                    return saved.getId();
                });
    }

    private UUID resolveDefaultCategoryId() {
        return fileCategoryRepository.findAll().stream()
                .findFirst()
                .map(FileCategory::getId)
                .orElseGet(this::createDefaultCategory);
    }

    private UUID createDefaultCategory() {
        FileCategory category = new FileCategory();
        category.setCode("LEGACY");
        category.setName("Legacy Migration");
        category.setDescription("Default category for files migrated from the legacy system");
        category.setIsActive(true);
        return fileCategoryRepository.save(category).getId();
    }

    private UUID resolveDefaultTypeId() {
        return fileTypeRepository.findAll().stream()
                .findFirst()
                .map(FileType::getId)
                .orElseGet(this::createDefaultType);
    }

    private UUID createDefaultType() {
        UUID categoryId = resolveDefaultCategoryId();
        FileType type = new FileType();
        type.setCategoryId(categoryId);
        type.setCode("LEGACY-FILE");
        type.setName("Legacy File");
        type.setDefaultExtension("*");
        type.setDescription("Default type for files migrated from the legacy system");
        type.setIsActive(true);
        return fileTypeRepository.save(type).getId();
    }

    private UUID resolveDefaultConfidentialityId() {
        return confidentialityLevelRepository.findAllByOrderByLevelOrderAsc().stream()
                .findFirst()
                .map(ConfidentialityLevel::getId)
                .orElse(null);
    }

    private int parseYearSanitized(Object rawYear) {
        if (rawYear == null) return defaultYear;
        String s = String.valueOf(rawYear).trim();
        if (s.isEmpty()) return defaultYear;
        try {
            int y = Integer.parseInt(s);
            return y >= 1900 ? y : defaultYear;
        } catch (NumberFormatException nfe) {
            return defaultYear;
        }
    }

    private String buildNotes(String originalNote, String originalYear, int usedYear) {
        StringBuilder sb = new StringBuilder();
        if (originalNote != null && !originalNote.isBlank()) {
            sb.append(originalNote);
        }
        if (usedYear != defaultYear && originalYear != null && !originalYear.equals(String.valueOf(usedYear))) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Original legacy year: ").append(originalYear);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String orgCode(int sideId) {
        return "LEGACY-" + sideId;
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
