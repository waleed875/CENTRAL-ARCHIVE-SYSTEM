package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.member.ProjectMember;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final String SCHEMA_VERSION = "1.0";

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final ProjectExportRepository exportRepository;
    private final ObjectMapper objectMapper;

    public ExportService(ProjectRepository projectRepository,
                         ProjectMemberRepository projectMemberRepository,
                         FileRepository fileRepository,
                         MinioService minioService,
                         ProjectExportRepository exportRepository,
                         ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.fileRepository = fileRepository;
        this.minioService = minioService;
        this.exportRepository = exportRepository;
        this.objectMapper = objectMapper;
    }

    public ProjectExport createExport(List<UUID> projectIds, UUID sourceOrganizationId, String exportedBy) {
        String packageUuid = UUID.randomUUID().toString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String packageName = "export_" + timestamp + ".pkg";

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("package_uuid", packageUuid);
        manifest.put("package_name", packageName);
        manifest.put("schema_version", SCHEMA_VERSION);
        manifest.put("source_organization_id", sourceOrganizationId != null ? sourceOrganizationId.toString() : null);
        manifest.put("source_terminal_uuid", packageUuid);
        manifest.put("source_username", exportedBy != null ? exportedBy : "system");

        ArrayNode projectsNode = manifest.putArray("projects");
        int totalFiles = 0;

        for (UUID projectId : projectIds) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));

            ObjectNode projectNode = projectsNode.addObject();
            projectNode.put("project_id", project.getId().toString());
            projectNode.put("project_number", project.getProjectNumber());
            projectNode.put("project_code", project.getProjectCode());
            projectNode.put("name", project.getName());
            projectNode.put("year", project.getYear());
            projectNode.put("executing_entity", project.getExecutingEntity());
            projectNode.put("supervisor_name", project.getSupervisorName());
            projectNode.put("description", project.getDescription());
            projectNode.put("notes", project.getNotes());
            projectNode.put("keywords", project.getKeywords());
            if (project.getProjectTypeId() != null) projectNode.put("project_type_id", project.getProjectTypeId().toString());
            if (project.getConfidentialityLevelId() != null) projectNode.put("confidentiality_level_id", project.getConfidentialityLevelId().toString());

            ArrayNode membersNode = projectNode.putArray("members");
            for (ProjectMember member : projectMemberRepository.findByProjectId(projectId)) {
                ObjectNode memberNode = membersNode.addObject();
                memberNode.put("person_name", member.getPersonName());
                memberNode.put("organization_name", member.getOrganizationName());
                memberNode.put("role_name", member.getRoleName());
                memberNode.put("notes", member.getNotes());
            }

            ArrayNode filesNode = projectNode.putArray("files");
            for (FileEntity file : fileRepository.findByProjectIdAndDeletedAtIsNull(projectId)) {
                ObjectNode fileNode = filesNode.addObject();
                fileNode.put("project_id", file.getProjectId().toString());
                fileNode.put("original_name", file.getOriginalName());
                fileNode.put("name", file.getOriginalName());
                fileNode.put("extension", file.getExtension());
                fileNode.put("mime_type", file.getMimeType());
                fileNode.put("size_bytes", file.getSizeBytes());
                fileNode.put("checksum_sha256", file.getChecksumSha256());
                if (file.getCategoryId() != null) fileNode.put("category_id", file.getCategoryId().toString());
                if (file.getTypeId() != null) fileNode.put("type_id", file.getTypeId().toString());
                if (file.getConfidentialityLevelId() != null) fileNode.put("confidentiality_level_id", file.getConfidentialityLevelId().toString());
                fileNode.put("object_key", file.getObjectKey());

                byte[] content = readFileBytes(file);
                String base64 = content != null ? Base64.getEncoder().encodeToString(content) : "";
                fileNode.put("content_base64", base64);
                totalFiles++;
            }
        }

        String manifestJson;
        try {
            manifestJson = objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize export manifest", e);
        }

        String packageHash = calculateSha256(manifestJson);

        ProjectExport export = new ProjectExport();
        export.setPackageUuid(packageUuid);
        export.setPackageName(packageName);
        export.setSchemaVersion(SCHEMA_VERSION);
        export.setSourceOrganizationId(sourceOrganizationId);
        export.setSourceUsername(exportedBy != null ? exportedBy : "system");
        export.setProjectsCount(projectIds.size());
        export.setFilesCount(totalFiles);
        export.setPackageHash(packageHash);
        export.setManifestData(manifestJson);
        export.setStatus("EXPORTED");
        export.setExportedBy(exportedBy);
        export.setExportedAt(LocalDateTime.now());

        ProjectExport saved = exportRepository.save(export);
        log.info("Package exported: id={}, packageUuid={}, projects={}, files={}",
                saved.getId(), packageUuid, projectIds.size(), totalFiles);
        return saved;
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

    private byte[] readFileBytes(FileEntity file) {
        try {
            InputStream is = minioService.downloadFile(file.getBucketName(), file.getObjectKey());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            is.close();
            return buffer.toByteArray();
        } catch (Exception e) {
            log.error("Failed to read file bytes for export: id={}, key={}", file.getId(), file.getObjectKey(), e);
            return null;
        }
    }

    private String calculateSha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate SHA-256", e);
        }
    }
}
