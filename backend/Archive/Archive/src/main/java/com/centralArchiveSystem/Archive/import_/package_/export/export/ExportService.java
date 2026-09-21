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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Transactional
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final String SCHEMA_VERSION = "1.0";
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

    public ProjectExport createExport(List<UUID> projectIds,
                                      UUID sourceOrganizationId,
                                      UUID exportedBy,
                                      String sourceUsername) {
        UUID packageUuid = UUID.randomUUID();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String packageName = "export_" + timestamp + "_" + packageUuid + ".pkg";
        String packageObjectKey = "exports/" + packageUuid + "/" + packageName;

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("package_uuid", packageUuid.toString());
        manifest.put("package_name", packageName);
        manifest.put("schema_version", SCHEMA_VERSION);
        if (sourceOrganizationId != null) {
            manifest.put("source_organization_id", sourceOrganizationId.toString());
        }
        manifest.put("source_terminal_uuid", archiveInstanceUuid.toString());
        manifest.put("source_username", sourceUsername != null ? sourceUsername : "system");

        ArrayNode projectsNode = manifest.putArray("projects");
        int totalFiles = 0;

        for (UUID projectId : projectIds) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));

            confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());

            ObjectNode projectNode = projectsNode.addObject();
            projectNode.put("project_id", project.getId().toString());
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

        byte[] manifestBytes;
        try {
            manifestBytes = objectMapper.writeValueAsBytes(manifest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize export manifest", e);
        }

        ProjectExport export = new ProjectExport();
        export.setPackageUuid(packageUuid);
        export.setPackageName(packageName);
        export.setSchemaVersion(SCHEMA_VERSION);
        export.setSourceOrganizationId(sourceOrganizationId);
        export.setSourceUsername(sourceUsername);
        export.setProjectsCount(projectIds.size());
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
            tempPackage = Files.createTempFile("central-archive-export-", ".pkg");
            writePackage(tempPackage, manifestBytes, projectsNode);

            String packageHash;
            try (InputStream input = Files.newInputStream(tempPackage)) {
                packageHash = packageArchiveService.sha256(input);
            }

            try (InputStream input = Files.newInputStream(tempPackage)) {
                minioService.uploadRaw(
                        input,
                        minioConfig.getBucket(),
                        packageObjectKey,
                        "application/octet-stream",
                        Files.size(tempPackage)
                );
            }

            saved.setPackageHash(packageHash);
            saved.setStatus("EXPORTED");
            saved = exportRepository.save(saved);

            log.info("Package exported: id={}, packageUuid={}, projects={}, files={}, objectKey={}",
                    saved.getId(), packageUuid, projectIds.size(), totalFiles, packageObjectKey);
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
        return exportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectExport", "id", id));
    }

    public List<ProjectExport> list(UUID sourceOrganizationId) {
        if (sourceOrganizationId != null) {
            return exportRepository.findBySourceOrganizationIdOrderByCreatedAtDesc(sourceOrganizationId);
        }
        return exportRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
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
