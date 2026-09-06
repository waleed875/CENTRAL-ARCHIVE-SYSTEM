package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
            LegacyMigrationItemRepository itemRepository) {
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
            project.setProjectUid("LEGACY-" + oldId);
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
        log.info("Legacy files migrated: {} done, {} failed, {} skipped", summary.done, summary.failed, summary.skipped);
        return summary;
    }

    private void migrateFilesForProject(int legacyProjectId, LegacyFileSummary summary) {
        String projectSourceKey = "PROJECT-" + legacyProjectId;
        UUID targetProjectId = itemRepository.findByKindAndSourceKey(LegacyMigrationItem.Kind.PROJECT, projectSourceKey)
                .map(LegacyMigrationItem::getTargetId)
                .orElse(null);

        if (targetProjectId == null) {
            log.warn("No target project for legacy project {}, skipping its files", legacyProjectId);
            return;
        }

        List<Map<String, Object>> files = legacyJdbc.queryForList(
                "SELECT idfile, filename, filesuffix, filebinary FROM " + LEGACY_DB_SCHEMA
                        + ".tbl_files WHERE idproject = ? ORDER BY idfile", legacyProjectId);

        for (Map<String, Object> fileRow : files) {
            int idfile = ((Number) fileRow.get("idfile")).intValue();
            String sourceKey = "FILE-" + idfile;
            if (itemRepository.existsByKindAndSourceKey(LegacyMigrationItem.Kind.FILE, sourceKey)) {
                summary.skipped++;
                continue;
            }
            migrateOneFile(idfile, sourceKey, targetProjectId, fileRow, summary);
        }
    }

    private void migrateOneFile(int idfile, String sourceKey, UUID targetProjectId,
                                Map<String, Object> fileRow, LegacyFileSummary summary) {
        LegacyMigrationItem item = trackingItem(LegacyMigrationItem.Kind.FILE, sourceKey, (long) idfile, null);
        item.setStatus(LegacyMigrationItem.Status.RUNNING);
        item.setStartedAt(java.time.LocalDateTime.now());
        itemRepository.save(item);

        try {
            String originalName = (String) fileRow.get("filename");
            String suffix = (String) fileRow.get("filesuffix");
            String base64Content = (String) fileRow.get("filebinary");

            String storedName = UUID.randomUUID() + (suffix != null ? suffix : "");
            String bucket = minioConfig.getBucket();
            String objectKey = minioPrefix + "/" + targetProjectId + "/" + storedName;

            byte[] content = base64Content != null ? Base64.getDecoder().decode(base64Content) : new byte[0];
            String checksum = sha256(content);
            String mime = resolveMime(suffix);

            minioService.uploadRaw(new ByteArrayInputStream(content), bucket, objectKey, mime, content.length);

            FileEntity file = new FileEntity();
            file.setProjectId(targetProjectId);
            file.setCategoryId(resolveDefaultCategoryId());
            file.setTypeId(resolveDefaultTypeId());
            file.setConfidentialityLevelId(resolveDefaultConfidentialityId());
            file.setOriginalName(originalName != null ? originalName : ("legacy-" + idfile));
            file.setStoredName(storedName);
            file.setBucketName(bucket);
            file.setObjectKey(objectKey);
            file.setExtension(suffix != null && suffix.startsWith(".") ? suffix.substring(1).toLowerCase() : suffix);
            file.setMimeType(mime);
            file.setSizeBytes((long) content.length);
            file.setChecksumSha256(checksum);
            file.setStatus("ACTIVE");
            file.setSourceType("MIGRATION");
            file.setSourceReference("ARC2:tbl_files:" + idfile);

            FileEntity saved = fileRepository.save(file);

            item.setTargetId(saved.getId());
            item.setStatus(LegacyMigrationItem.Status.DONE);
            item.setFinishedAt(java.time.LocalDateTime.now());
            itemRepository.save(item);
            summary.done++;
        } catch (Exception e) {
            log.error("Failed to migrate file {} ({}): {}", idfile, fileRow.get("filename"), e.getMessage(), e);
            item.setStatus(LegacyMigrationItem.Status.FAILED);
            item.setError(e.getMessage());
            item.setFinishedAt(java.time.LocalDateTime.now());
            item.setAttempts((item.getAttempts() == null ? 0 : item.getAttempts()) + 1);
            itemRepository.save(item);
            summary.failed++;
        }
    }

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

    private String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        return hex.toString();
    }

    private String resolveMime(String suffix) {
        if (suffix == null) return DEFAULT_MIME;
        String s = suffix.toLowerCase();
        return switch (s) {
            case ".pdf" -> "application/pdf";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".txt" -> "text/plain";
            case ".sql" -> "application/sql";
            case ".rar" -> "application/vnd.rar";
            case ".zip" -> "application/zip";
            case ".exe" -> "application/x-msdownload";
            default -> DEFAULT_MIME;
        };
    }

    public static class LegacyFileSummary {
        public int done;
        public int failed;
        public int skipped;
    }
}
