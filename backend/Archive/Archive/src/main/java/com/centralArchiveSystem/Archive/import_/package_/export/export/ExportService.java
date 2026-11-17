package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.package_.PackageArchiveService;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.member.ProjectMember;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Transactional
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final String SCHEMA_VERSION = "1.0";
    private static final String VERSIONED_SCHEMA_VERSION = "2.0";
    private static final int BUFFER_SIZE = 8192;

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final MinioConfig minioConfig;
    private final ProjectExportRepository exportRepository;
    private final ObjectMapper objectMapper;
    private final PackageArchiveService packageArchiveService;
    private final ConfidentialityAccessService confidentialityAccessService;
    private final UUID archiveInstanceUuid;
    // Optional field injection preserves the legacy constructor used in isolated unit tests.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExportAlternativeWriter alternativeWriter;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExportEncryptedPackageWriter encryptedWriter;

    public ExportService(ProjectRepository projectRepository,
                         ProjectMemberRepository projectMemberRepository,
                         FileRepository fileRepository,
                         MinioService minioService,
                         MinioConfig minioConfig,
                         ProjectExportRepository exportRepository,
                         ObjectMapper objectMapper,
                         PackageArchiveService packageArchiveService,
                         ConfidentialityAccessService confidentialityAccessService,
                         @Value("${archive.instance.uuid:00000000-0000-0000-0000-000000000001}") UUID archiveInstanceUuid) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.fileRepository = fileRepository;
        this.minioService = minioService;
        this.minioConfig = minioConfig;
        this.exportRepository = exportRepository;
        this.objectMapper = objectMapper;
        this.packageArchiveService = packageArchiveService;
        this.confidentialityAccessService = confidentialityAccessService;
        this.archiveInstanceUuid = archiveInstanceUuid;
    }

    /** Retains legacy exact-row export for older callers and existing 1.0 packages. */
    public ProjectExport createExport(List<UUID> projectIds, UUID exportedBy, String sourceUsername) {
        return createExport(new ExportRequest(projectIds), exportedBy, sourceUsername);
    }

    @Transactional(readOnly = true)
    public ExportPreviewResponse preview(ExportRequest request) {
        List<Project> selected = resolveProjects(request);
        Map<UUID, List<Project>> families = new LinkedHashMap<>();
        for (Project project : selected) {
            UUID key = project.getProjectUid() != null ? project.getProjectUid() : project.getId();
            families.computeIfAbsent(key, ignored -> new ArrayList<>()).add(project);
        }
        int totalFiles = 0;
        long totalBytes = 0;
        List<ExportPreviewResponse.Family> summary = new ArrayList<>();
        for (Map.Entry<UUID, List<Project>> entry : families.entrySet()) {
            int filesCount = 0;
            long bytes = 0;
            for (Project project : entry.getValue()) {
                for (FileEntity file : fileRepository.findByProjectIdAndDeletedAtIsNull(project.getId())) {
                if (!"ACTIVE".equalsIgnoreCase(file.getStatus())) continue;
                    confidentialityAccessService.requireReadAccess(file.getConfidentialityLevelId());
                    filesCount++;
                    bytes += file.getSizeBytes() == null ? 0 : file.getSizeBytes();
                }
            }
            Project newest = entry.getValue().get(entry.getValue().size() - 1);
            summary.add(new ExportPreviewResponse.Family(entry.getKey(), newest.getName(),
                    entry.getValue().size(), filesCount, bytes));
            totalFiles += filesCount;
            totalBytes += bytes;
        }
        return new ExportPreviewResponse(new LinkedHashSet<>(request.projectIds()).size(),
                families.size(), selected.size(), totalFiles, totalBytes,
                request.selectedFormat(), request.allVersions() ? "ALL" : "EXACT", true, summary);
    }

    /**
     * Fetches the COMPLETE version family, not findAccessibleVersions(), which silently
     * omits confidential rows. A single hidden version must fail the entire export.
     */
    private List<Project> resolveProjects(ExportRequest request) {
        if (request == null) throw new IllegalArgumentException("Export request is required");
        request.validateOptions();
        List<Project> resolved = new ArrayList<>();
        Set<UUID> familiesSeen = new LinkedHashSet<>();
        for (UUID requestedId : new LinkedHashSet<>(request.projectIds())) {
            Project requested = projectRepository.findById(requestedId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project", "id", requestedId));
            confidentialityAccessService.requireReadAccess(requested.getConfidentialityLevelId());
            // Rejected versions are not exportable, including when they are part of an
            // otherwise eligible logical project's complete version history.
            if (requested.getStatus() == Project.ProjectStatus.REJECTED) {
                throw new IllegalArgumentException("لا يمكن تصدير مشروع مرفوض: " + requested.getName());
            }
            if (!request.allVersions()) {
                resolved.add(requested);
                continue;
            }
            UUID familyId = requested.getProjectUid();
            if (familyId == null) {
                throw new IllegalArgumentException("Selected project has no version family: " + requestedId);
            }
            if (!familiesSeen.add(familyId)) continue;
            List<Project> versions = projectRepository.findByProjectUidOrderByVersionNoAsc(familyId);
            if (versions.isEmpty()) throw new IllegalStateException("Project version family is empty");
            for (int i = 0; i < versions.size(); i++) {
                Project current = versions.get(i);
                confidentialityAccessService.requireReadAccess(current.getConfidentialityLevelId());
                if (current.getStatus() == Project.ProjectStatus.REJECTED) {
                    throw new IllegalArgumentException("لا يمكن تصدير سجل إصدارات يحتوي على إصدار مرفوض: "
                            + current.getName() + " (v" + current.getVersionNo() + ")");
                }
                if (!Objects.equals(current.getProjectUid(), familyId)
                        || !Objects.equals(current.getVersionNo(), i + 1)
                        || !Objects.equals(current.getPreviousProjectId(), i == 0 ? null : versions.get(i-1).getId())
                        || Boolean.TRUE.equals(current.getIsLatestVersion()) != (i == versions.size() - 1)) {
                    throw new IllegalStateException("Project version history is incomplete or inconsistent: " + familyId);
                }
            }
            resolved.addAll(versions);
        }
        return resolved;
    }

    public ProjectExport createExport(ExportRequest request,
                                      UUID exportedBy,
                                      String sourceUsername) {
        List<Project> selectedProjects = resolveProjects(request);
        boolean withVersions = request.allVersions();
        String schemaVersion = withVersions ? VERSIONED_SCHEMA_VERSION : SCHEMA_VERSION;
        UUID packageUuid = UUID.randomUUID();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String format = request.selectedFormat();
        String extension = ExportFormatPolicy.extension(format, request.encrypted());
        String packageName = "export_" + timestamp + "_" + packageUuid + "." + extension;
        String packageObjectKey = "exports/" + packageUuid + "/" + packageName;

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("package_uuid", packageUuid.toString());
        manifest.put("package_name", packageName);
        manifest.put("schema_version", schemaVersion);
        manifest.put("source_terminal_uuid", archiveInstanceUuid.toString());
        manifest.put("source_username", sourceUsername != null ? sourceUsername : "system");

        ArrayNode projectsNode = manifest.putArray("projects");
        int totalFiles = 0;
        Set<UUID> sourceOrganizationIds = new LinkedHashSet<>();

        for (Project project : selectedProjects) {
            UUID projectId = project.getId();
            // resolveProjects already verified clearance for every version, without
            // silently filtering high-confidentiality historical records.
            sourceOrganizationIds.add(project.getOrganizationId());

            ObjectNode projectNode = projectsNode.addObject();
            projectNode.put("project_id", project.getId().toString());
            if (withVersions) {
                projectNode.put("project_uid", project.getProjectUid().toString());
                projectNode.put("version_no", project.getVersionNo());
                projectNode.put("is_latest_version", Boolean.TRUE.equals(project.getIsLatestVersion()));
                putNullable(projectNode, "previous_project_id",
                        project.getPreviousProjectId() == null ? null : project.getPreviousProjectId().toString());
                projectNode.put("source_status", project.getStatus().name());
            }
            projectNode.put("organization_id", project.getOrganizationId().toString());
            projectNode.put("project_number", project.getProjectNumber());
            putNullable(projectNode, "project_code", project.getProjectCode());
            projectNode.put("name", project.getName());
            projectNode.put("year", project.getYear());
            putNullable(projectNode, "executing_entity", project.getExecutingEntity());
            putNullable(projectNode, "supervisor_name", project.getSupervisorName());
            putNullable(projectNode, "description", project.getDescription());
            putNullable(projectNode, "notes", project.getNotes());
            putNullable(projectNode, "table_of_contents", project.getTableOfContents());
            putNullable(projectNode, "keywords", project.getKeywords());

            if (project.getProjectTypeId() != null) {
                projectNode.put("project_type_id", project.getProjectTypeId().toString());
            }
            if (project.getConfidentialityLevelId() != null) {
                projectNode.put("confidentiality_level_id", project.getConfidentialityLevelId().toString());
            }

            ArrayNode membersNode = projectNode.putArray("members");
            for (ProjectMember member : projectMemberRepository.findByProjectId(projectId)) {
                ObjectNode memberNode = membersNode.addObject();
                memberNode.put("person_name", member.getPersonName());
                putNullable(memberNode, "organization_name", member.getOrganizationName());
                putNullable(memberNode, "role_name", member.getRoleName());
                putNullable(memberNode, "notes", member.getNotes());
            }

            ArrayNode filesNode = projectNode.putArray("files");
            for (FileEntity file : fileRepository.findByProjectIdAndDeletedAtIsNull(projectId)) {
                if (!"ACTIVE".equalsIgnoreCase(file.getStatus())) continue;
                confidentialityAccessService.requireReadAccess(file.getConfidentialityLevelId());

                String checksum = file.getChecksumSha256();
                if (checksum == null || checksum.isBlank()) {
                    checksum = calculateStoredFileChecksum(file);
                }

                String packageEntryPath = packageEntryPath(project, file);

                ObjectNode fileNode = filesNode.addObject();
                fileNode.put("project_id", project.getId().toString());
                fileNode.put("file_id", file.getId().toString());
                fileNode.put("original_name", file.getOriginalName());
                fileNode.put("name", file.getOriginalName());
                putNullable(fileNode, "extension", file.getExtension());
                putNullable(fileNode, "mime_type", file.getMimeType());
                if (file.getSizeBytes() != null) {
                    fileNode.put("size_bytes", file.getSizeBytes());
                }
                fileNode.put("checksum_sha256", checksum);
                if (file.getCategoryId() != null) {
                    fileNode.put("category_id", file.getCategoryId().toString());
                }
                if (file.getTypeId() != null) {
                    fileNode.put("type_id", file.getTypeId().toString());
                }
                if (file.getConfidentialityLevelId() != null) {
                    fileNode.put("confidentiality_level_id", file.getConfidentialityLevelId().toString());
                }
                fileNode.put("object_key", packageEntryPath);
                totalFiles++;
            }
        }

        UUID sourceOrganizationId = sourceOrganizationIds.size() == 1
                ? sourceOrganizationIds.iterator().next()
                : null;
        if (sourceOrganizationId != null) {
            manifest.put("source_organization_id", sourceOrganizationId.toString());
        }

        byte[] manifestBytes;
        try {
            manifestBytes = objectMapper.writeValueAsBytes(manifest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize export manifest", e);
        }

        ProjectExport export = new ProjectExport();
        export.setPackageUuid(packageUuid);
        export.setPackageName(packageName);
        export.setSchemaVersion(schemaVersion);
        export.setSourceOrganizationId(sourceOrganizationId);
        export.setSourceUsername(sourceUsername);
        export.setProjectsCount(selectedProjects.size());
        export.setFilesCount(totalFiles);
        export.setManifestData(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8));
        export.setManifestHash(sha256(manifestBytes));
        export.setPackageObjectKey(packageObjectKey);
        export.setStatus("CREATED");
        export.setExportedBy(exportedBy);
        export.setExportedAt(LocalDateTime.now());

        ProjectExport saved = exportRepository.save(export);
        Path tempPackage = null;

        try {
            tempPackage = Files.createTempFile("central-archive-export-", "." + extension);
            if (request.encrypted()) {
                if (encryptedWriter == null) throw new IllegalStateException("Encrypted export writer is not configured");
                Path plain = Files.createTempFile("central-archive-inner-", ".pkg");
                try {
                    writePackage(plain, manifestBytes, projectsNode);
                    encryptedWriter.seal(plain, tempPackage, manifest, request.exportPassphrase());
                } finally {
                    Files.deleteIfExists(plain);
                }
            } else if ("PKG".equals(format)) {
                writePackage(tempPackage, manifestBytes, projectsNode);
            } else {
                if (alternativeWriter == null) throw new IllegalStateException("Export report writer is not configured");
                alternativeWriter.write(format, tempPackage, manifest);
            }

            String packageHash;
            try (InputStream input = Files.newInputStream(tempPackage)) {
                packageHash = packageArchiveService.sha256(input);
            }

            try (InputStream input = Files.newInputStream(tempPackage)) {
                minioService.uploadRaw(
                        input,
                        minioConfig.getBucket(),
                        packageObjectKey,
                        ExportFormatPolicy.mediaType(packageName).toString(),
                        Files.size(tempPackage)
                );
            }
            registerRollbackObjectCleanup(minioConfig.getBucket(), packageObjectKey);

            saved.setPackageHash(packageHash);
            saved.setStatus("EXPORTED");
            saved = exportRepository.save(saved);

            log.info("Package exported: id={}, packageUuid={}, projects={}, files={}, objectKey={}",
                    saved.getId(), packageUuid, selectedProjects.size(), totalFiles, packageObjectKey);
            return saved;
        } catch (Exception e) {
            log.error("Failed to create export package {}", packageUuid, e);
            saved.setStatus("FAILED");
            exportRepository.save(saved);
            throw new RuntimeException("Failed to create export package: " + e.getMessage(), e);
        } finally {
            if (tempPackage != null) {
                try {
                    Files.deleteIfExists(tempPackage);
                } catch (Exception cleanupError) {
                    log.warn("Failed to remove temporary export package {}", tempPackage, cleanupError);
                }
            }
        }
    }

    public ProjectExport getById(UUID id) {
        ProjectExport export = exportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectExport", "id", id));
        validateManifestClearance(export.getManifestData());
        return export;
    }

    public List<ProjectExport> list(UUID sourceOrganizationId) {
        List<ProjectExport> exports = sourceOrganizationId != null
                ? exportRepository.findBySourceOrganizationIdOrderByCreatedAtDesc(sourceOrganizationId)
                : exportRepository.findAll().stream()
                        .sorted(Comparator.comparing(
                                ProjectExport::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        ).reversed())
                        .toList();

        return exports.stream()
                .filter(this::canReadExportManifest)
                .toList();
    }

    public InputStream downloadPackage(UUID id) {
        ProjectExport export = getById(id);
        if (!"EXPORTED".equals(export.getStatus())) {
            throw new IllegalStateException("Export package is not ready for download");
        }

        validateManifestClearance(export.getManifestData());

        try {
            return minioService.downloadFile(
                    minioConfig.getBucket(),
                    export.getPackageObjectKey()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to download export package: " + e.getMessage(), e);
        }
    }

    private void registerRollbackObjectCleanup(String bucket, String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                            return;
                        }
                        try {
                            minioService.deleteFile(bucket, objectKey);
                        } catch (Exception cleanupError) {
                            log.error(
                                    "Failed to remove export object after transaction rollback: {}",
                                    objectKey,
                                    cleanupError
                            );
                        }
                    }
                }
        );
    }

    private boolean canReadExportManifest(ProjectExport export) {
        try {
            validateManifestClearance(export.getManifestData());
            return true;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return false;
        } catch (RuntimeException e) {
            log.warn("Excluding export {} with unreadable manifest", export.getId(), e);
            return false;
        }
    }

    private void validateManifestClearance(String manifestJson) {
        try {
            JsonNode manifest = objectMapper.readTree(manifestJson);
            JsonNode projects = manifest.path("projects");
            if (!projects.isArray()) {
                return;
            }

            for (JsonNode project : projects) {
                confidentialityAccessService.requireReadAccess(
                        optionalUuid(project, "confidentiality_level_id")
                );

                JsonNode files = project.path("files");
                if (!files.isArray()) {
                    continue;
                }

                for (JsonNode file : files) {
                    confidentialityAccessService.requireReadAccess(
                            optionalUuid(file, "confidentiality_level_id")
                    );
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Stored export manifest is invalid", e);
        }
    }

    private void writePackage(Path tempPackage,
                              byte[] manifestBytes,
                              ArrayNode projectsNode) throws Exception {
        try (OutputStream output = Files.newOutputStream(tempPackage);
             ZipOutputStream zip = new ZipOutputStream(output)) {

            ZipEntry manifestEntry = new ZipEntry("manifest.json");
            zip.putNextEntry(manifestEntry);
            zip.write(manifestBytes);
            zip.closeEntry();

            for (JsonNode projectNode : projectsNode) {
                JsonNode files = projectNode.path("files");
                if (!files.isArray()) {
                    continue;
                }

                UUID projectId = UUID.fromString(projectNode.path("project_id").asText());

                for (JsonNode fileNode : files) {
                    String entryPath = fileNode.path("object_key").asText();
                    String expectedHash = fileNode.path("checksum_sha256").asText();
                    long expectedSize = fileNode.path("size_bytes").asLong(-1);

                    UUID fileId = UUID.fromString(fileNode.path("file_id").asText());
                    FileEntity file = fileRepository.findById(fileId)
                            .filter(candidate -> candidate.getProjectId().equals(projectId))
                            .filter(candidate -> candidate.getDeletedAt() == null)
                            .filter(candidate -> "ACTIVE".equalsIgnoreCase(candidate.getStatus()))
                            .orElseThrow(() -> new IllegalStateException(
                                    "Source file not found while creating package: " + fileId));

                    ZipEntry fileEntry = new ZipEntry(entryPath);
                    zip.putNextEntry(fileEntry);

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long copied = 0L;
                    byte[] buffer = new byte[BUFFER_SIZE];

                    try (InputStream input = minioService.downloadFile(
                            file.getBucketName(),
                            file.getObjectKey())) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            zip.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            copied += read;
                        }
                    }

                    zip.closeEntry();

                    String actualHash = HexFormat.of().formatHex(digest.digest());
                    if (!expectedHash.equalsIgnoreCase(actualHash)) {
                        throw new IllegalStateException(
                                "Stored file checksum changed before export: " + file.getId());
                    }
                    if (expectedSize >= 0 && expectedSize != copied) {
                        throw new IllegalStateException(
                                "Stored file size changed before export: " + file.getId());
                    }
                }
            }
        }
    }

    private String calculateStoredFileChecksum(FileEntity file) {
        try (InputStream input = minioService.downloadFile(file.getBucketName(), file.getObjectKey())) {
            return packageArchiveService.sha256(input);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate checksum for file " + file.getId(), e);
        }
    }

    private String packageEntryPath(Project project, FileEntity file) {
        String safeName = file.getOriginalName() != null
                ? file.getOriginalName().replaceAll("[^A-Za-z0-9._-]", "_")
                : "file";
        return "files/" + project.getId() + "/" + file.getId() + "-" + safeName;
    }

    private UUID optionalUuid(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate SHA-256", e);
        }
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
