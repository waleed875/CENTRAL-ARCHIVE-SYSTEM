package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageResponse;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageVerifyResponse;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.member.ProjectMember;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class BranchPackageService {

    private static final Logger log = LoggerFactory.getLogger(BranchPackageService.class);

    private final BranchPackageRepository branchPackageRepository;
    private final PackageProjectRepository packageProjectRepository;
    private final ObjectMapper objectMapper;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final MinioConfig minioConfig;

    public BranchPackageService(BranchPackageRepository branchPackageRepository,
                                PackageProjectRepository packageProjectRepository,
                                ObjectMapper objectMapper,
                                ProjectRepository projectRepository,
                                ProjectMemberRepository projectMemberRepository,
                                FileRepository fileRepository,
                                MinioService minioService,
                                MinioConfig minioConfig) {
        this.branchPackageRepository = branchPackageRepository;
        this.packageProjectRepository = packageProjectRepository;
        this.objectMapper = objectMapper;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.fileRepository = fileRepository;
        this.minioService = minioService;
        this.minioConfig = minioConfig;
    }

    @Transactional(readOnly = true)
    public Page<PackageResponse> list(String searchTerm,
                                       BranchPackage.PackageStatus status,
                                       UUID sourceOrganizationId,
                                       int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<BranchPackage> packages = branchPackageRepository.search(
                searchTerm, status, sourceOrganizationId, pageable);
        return packages.map(PackageResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public PackageResponse getById(UUID id) {
        BranchPackage pkg = branchPackageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BranchPackage", "id", id));
        return PackageResponse.fromEntity(pkg);
    }

    public PackageResponse receive(String manifestJson, String receivedBy) {
        try {
            JsonNode manifest = objectMapper.readTree(manifestJson);

            String packageUuid = manifest.path("package_uuid").asText();
            if (packageUuid == null || packageUuid.isBlank()) {
                throw new IllegalArgumentException("Missing package_uuid in manifest");
            }

            if (branchPackageRepository.existsByPackageUuid(packageUuid)) {
                BranchPackage existing = branchPackageRepository.findByPackageUuid(packageUuid)
                        .orElseThrow();
                existing.setStatus(BranchPackage.PackageStatus.DUPLICATE);
                branchPackageRepository.save(existing);
                log.info("Duplicate package received: packageUuid={}", packageUuid);
                return PackageResponse.fromEntity(existing);
            }

            String packageName = manifest.path("package_name").asText("Unnamed Package");
            String schemaVersion = manifest.path("schema_version").asText("1.0");
            String sourceOrganizationIdStr = manifest.path("source_organization_id").asText();
            String sourceTerminalUuid = manifest.path("source_terminal_uuid").asText();
            String sourceUsername = manifest.path("source_username").asText();

            UUID sourceOrgId = sourceOrganizationIdStr != null && !sourceOrganizationIdStr.isBlank()
                    ? UUID.fromString(sourceOrganizationIdStr) : null;

            JsonNode projectsNode = manifest.path("projects");
            int projectsCount = projectsNode.isArray() ? projectsNode.size() : 0;
            int filesCount = 0;
            if (projectsNode.isArray()) {
                for (JsonNode projectNode : projectsNode) {
                    JsonNode filesNode = projectNode.path("files");
                    if (filesNode.isArray()) {
                        filesCount += filesNode.size();
                    }
                }
            }

            String manifestHash = calculateSha256(manifestJson);

            BranchPackage pkg = new BranchPackage();
            pkg.setPackageUuid(packageUuid);
            pkg.setSourceOrganizationId(sourceOrgId);
            pkg.setSourceTerminalUuid(sourceTerminalUuid);
            pkg.setSourceUsername(sourceUsername);
            pkg.setPackageName(packageName);
            pkg.setPackageHash(manifestHash);
            pkg.setManifestHash(manifestHash);
            pkg.setSchemaVersion(schemaVersion);
            pkg.setProjectsCount(projectsCount);
            pkg.setFilesCount(filesCount);
            pkg.setStatus(BranchPackage.PackageStatus.RECEIVED);
            pkg.setReceivedBy(receivedBy);
            pkg.setReceivedAt(LocalDateTime.now());
            pkg.setManifestData(manifestJson);

            BranchPackage saved = branchPackageRepository.save(pkg);
            log.info("Package received: id={}, packageUuid={}, projects={}, files={}",
                    saved.getId(), packageUuid, projectsCount, filesCount);

            persistPackageProjects(saved.getId(), projectsNode);

            return PackageResponse.fromEntity(saved);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse package manifest", e);
            throw new RuntimeException("Failed to parse package manifest: " + e.getMessage(), e);
        }
    }

    public PackageVerifyResponse verify(UUID id) {
        BranchPackage pkg = branchPackageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BranchPackage", "id", id));

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean manifestHashValid = true;
        boolean packageIntegrityValid = true;
        boolean schemaVersionValid = true;

        if (pkg.getManifestData() == null || pkg.getManifestData().isBlank()) {
            errors.add("Manifest data is missing or empty");
            manifestHashValid = false;
        } else {
            try {
                String recalculatedHash = calculateSha256(pkg.getManifestData());
                if (!recalculatedHash.equals(pkg.getManifestHash())) {
                    errors.add("Manifest hash mismatch: expected " + pkg.getManifestHash() + " but got " + recalculatedHash);
                    manifestHashValid = false;
                }
            } catch (Exception e) {
                errors.add("Failed to calculate manifest hash: " + e.getMessage());
                manifestHashValid = false;
            }
        }

        if (pkg.getSchemaVersion() == null || pkg.getSchemaVersion().isBlank()) {
            errors.add("Schema version is missing");
            schemaVersionValid = false;
        } else {
            try {
                String[] parts = pkg.getSchemaVersion().split("\\.");
                if (parts.length < 2) {
                    warnings.add("Schema version format is unusual: " + pkg.getSchemaVersion());
                }
            } catch (Exception e) {
                schemaVersionValid = false;
            }
        }

        if (pkg.getProjectsCount() == null || pkg.getProjectsCount() == 0) {
            warnings.add("Package contains no projects");
        }

        if (pkg.getFilesCount() == null || pkg.getFilesCount() == 0) {
            warnings.add("Package contains no files");
        }

        long savedProjectsCount = packageProjectRepository.countByPackageId(id);
        if (savedProjectsCount != pkg.getProjectsCount()) {
            warnings.add("Stored project records (" + savedProjectsCount + ") differ from manifest count (" + pkg.getProjectsCount() + ")");
        }

        List<PackageProject> projectRecords = packageProjectRepository.findByPackageId(id);
        List<PackageVerifyResponse.ProjectManifestDetail> projectDetails = projectRecords.stream()
                .map(pp -> new PackageVerifyResponse.ProjectManifestDetail(
                        pp.getProjectId(),
                        pp.getSourceProjectNumber(),
                        pp.getProjectName(),
                        pp.getProjectYear(),
                        pp.getFilesCount(),
                        pp.getImportStatus() == PackageProject.ImportStatus.DUPLICATE,
                        pp.getLocalProjectUuid()
                ))
                .collect(Collectors.toList());

        boolean overallValid = errors.isEmpty() && manifestHashValid && schemaVersionValid;

        if (overallValid) {
            pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
            pkg.setVerifiedAt(LocalDateTime.now());
            branchPackageRepository.save(pkg);
        }

        log.info("Package verified: id={}, valid={}, errors={}, warnings={}",
                id, overallValid, errors.size(), warnings.size());

        return new PackageVerifyResponse(
                pkg.getId(),
                pkg.getPackageUuid(),
                overallValid,
                manifestHashValid,
                packageIntegrityValid,
                schemaVersionValid,
                pkg.getProjectsCount(),
                pkg.getFilesCount(),
                LocalDateTime.now(),
                errors,
                warnings,
                projectDetails,
                null
        );
    }

    @Async
    public void importPackage(UUID id, PackageImportRequest request) {
        BranchPackage pkg = branchPackageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BranchPackage", "id", id));

        if (pkg.getStatus() != BranchPackage.PackageStatus.VERIFIED) {
            log.warn("Package {} is not verified, cannot import. Current status: {}", id, pkg.getStatus());
            return;
        }

        try {
            UUID targetOrgId = request.targetOrganizationId();
            pkg.setTargetOrganizationId(targetOrgId);

            Map<UUID, UUID> orgMapping = request.organizationMapping() != null
                    ? request.organizationMapping() : new HashMap<>();

            List<PackageProject> projects = packageProjectRepository.findByPackageId(id);

            int importedProjects = 0;
            int failedProjects = 0;

            for (PackageProject pp : projects) {
                try {
                    if (pp.getImportStatus() == PackageProject.ImportStatus.DUPLICATE && request.skipDuplicates()) {
                        log.info("Skipping duplicate project: {}", pp.getProjectId());
                        pp.setImportStatus(PackageProject.ImportStatus.SKIPPED);
                        packageProjectRepository.save(pp);
                        continue;
                    }

                    UUID resolvedOrgId = targetOrgId;
                    if (pkg.getSourceOrganizationId() != null && orgMapping.containsKey(pkg.getSourceOrganizationId())) {
                        resolvedOrgId = orgMapping.get(pkg.getSourceOrganizationId());
                    }

                    JsonNode manifest = objectMapper.readTree(pkg.getManifestData());
                    JsonNode projectsNode = manifest.path("projects");
                    JsonNode projectNode = null;
                    if (projectsNode.isArray()) {
                        for (JsonNode pn : projectsNode) {
                            if (pp.getProjectId().equals(pn.path("project_id").asText())) {
                                projectNode = pn;
                                break;
                            }
                        }
                    }

                    Project project = buildProject(projectNode, pp, resolvedOrgId, pkg);
                    Project savedProject = projectRepository.save(project);

                    createProjectMembers(projectNode, savedProject.getId());

                    int totalFiles = 0;
                    int importedFiles = 0;
                    int failedFiles = 0;
                    if (projectNode != null && projectNode.path("files").isArray()) {
                        for (JsonNode fileNode : projectNode.path("files")) {
                            totalFiles++;
                            try {
                                importFile(fileNode, savedProject.getId(), pkg);
                                importedFiles++;
                            } catch (Exception e) {
                                failedFiles++;
                                log.error("Failed to import file for project {}", savedProject.getId(), e);
                            }
                        }
                    }

                    pp.setLocalProjectUuid(savedProject.getId());
                    pp.setImportStatus(PackageProject.ImportStatus.IMPORTED);
                    pp.setFilesCount(totalFiles);
                    pp.setFilesImported(importedFiles);
                    pp.setFilesFailed(failedFiles);
                    packageProjectRepository.save(pp);
                    importedProjects++;
                    log.info("Imported project: packageProjectId={}, localProjectId={}, files={}", pp.getId(), savedProject.getId(), importedFiles);

                } catch (Exception e) {
                    log.error("Failed to import project: {}", pp.getProjectId(), e);
                    pp.setImportStatus(PackageProject.ImportStatus.FAILED);
                    pp.setImportError(e.getMessage());
                    packageProjectRepository.save(pp);
                    failedProjects++;
                }
            }

            pkg.setImportedAt(LocalDateTime.now());
            pkg.setReceivedBy(request.importedBy() != null ? request.importedBy() : pkg.getReceivedBy());
            pkg.setStatus(failedProjects > 0 && importedProjects > 0
                    ? BranchPackage.PackageStatus.IMPORTED
                    : (failedProjects > 0 ? BranchPackage.PackageStatus.FAILED : BranchPackage.PackageStatus.IMPORTED));
            if (failedProjects > 0 && importedProjects == 0) {
                pkg.setErrorMessage("All projects failed to import");
            }
            branchPackageRepository.save(pkg);
            log.info("Package import completed: id={}, imported={}, failed={}", id, importedProjects, failedProjects);

        } catch (Exception e) {
            log.error("Package import failed: id={}", id, e);
            pkg.setStatus(BranchPackage.PackageStatus.FAILED);
            pkg.setErrorMessage(e.getMessage());
            branchPackageRepository.save(pkg);
        }
    }

    private Project buildProject(JsonNode projectNode, PackageProject pp, UUID orgId, BranchPackage pkg) {
        Project project = new Project();
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setOrganizationId(orgId);
        project.setProjectNumber(pp.getSourceProjectNumber() != null ? pp.getSourceProjectNumber() : pp.getProjectId());
        project.setName(pp.getProjectName() != null ? pp.getProjectName() : "Imported Project");
        project.setYear(pp.getProjectYear() != null ? pp.getProjectYear() : LocalDateTime.now().getYear());
        project.setProjectCode(projectNode != null ? projectNode.path("project_code").asText(null) : null);
        project.setExecutingEntity(projectNode != null ? projectNode.path("executing_entity").asText(null) : null);
        project.setSupervisorName(projectNode != null ? projectNode.path("supervisor_name").asText(null) : null);
        project.setDescription(projectNode != null ? projectNode.path("description").asText(null) : null);
        project.setNotes(projectNode != null ? projectNode.path("notes").asText(null) : null);
        project.setKeywords(projectNode != null ? projectNode.path("keywords").asText(null) : null);
        if (projectNode != null && projectNode.hasNonNull("project_type_id")) {
            try { project.setProjectTypeId(UUID.fromString(projectNode.path("project_type_id").asText())); } catch (Exception ignored) {}
        }
        if (projectNode != null && projectNode.hasNonNull("confidentiality_level_id")) {
            try { project.setConfidentialityLevelId(UUID.fromString(projectNode.path("confidentiality_level_id").asText())); } catch (Exception ignored) {}
        }
        project.setStatus(Project.ProjectStatus.IMPORTED);
        project.setDataCompletionStatus(Project.DataCompletionStatus.COMPLETE);
        project.setSourceType(Project.SourceType.BRANCH_PACKAGE);
        project.setSourceReference(pkg.getPackageUuid());
        project.setCreatedBy(pkg.getSourceUsername());
        return project;
    }

    private void createProjectMembers(JsonNode projectNode, UUID projectId) {
        if (projectNode == null || !projectNode.path("members").isArray()) return;
        for (JsonNode memberNode : projectNode.path("members")) {
            ProjectMember member = new ProjectMember();
            member.setProjectId(projectId);
            member.setPersonName(memberNode.path("person_name").asText(memberNode.path("name").asText("Unknown")));
            member.setOrganizationName(memberNode.path("organization_name").asText(null));
            member.setRoleName(memberNode.path("role_name").asText(null));
            member.setNotes(memberNode.path("notes").asText(null));
            projectMemberRepository.save(member);
        }
    }

    private void importFile(JsonNode fileNode, UUID projectId, BranchPackage pkg) throws Exception {
        String originalName = fileNode.path("original_name").asText(fileNode.path("name").asText("file"));
        String extension = fileNode.path("extension").asText(null);
        String mimeType = fileNode.path("mime_type").asText("application/octet-stream");
        long sizeBytes = fileNode.path("size_bytes").asLong(0);
        String checksum = fileNode.path("checksum_sha256").asText(null);
        String sourceOrgIdStr = pkg.getSourceOrganizationId() != null
                ? pkg.getSourceOrganizationId().toString() : pkg.getPackageUuid();
        String localPath = fileNode.path("local_path").asText(null);
        String contentKey = fileNode.path("object_key").asText(
                localPath != null ? localPath : fileNode.path("project_id").asText() + "/" + originalName);

        String storedName = UUID.randomUUID() + (extension != null ? "." + extension : "");
        String bucketName = minioConfig.getBucket();
        String objectKey = "import/" + pkg.getPackageUuid() + "/" + projectId + "/" + storedName;

        byte[] content = Base64.getDecoder().decode(fileNode.path("content_base64").asText(""));
        InputStream is = new ByteArrayInputStream(content);
        minioService.uploadRaw(is, bucketName, objectKey, mimeType, content.length);

        FileEntity file = new FileEntity();
        file.setProjectId(projectId);
        if (fileNode.hasNonNull("category_id")) {
            try { file.setCategoryId(UUID.fromString(fileNode.path("category_id").asText())); } catch (Exception ignored) {}
        }
        if (fileNode.hasNonNull("type_id")) {
            try { file.setTypeId(UUID.fromString(fileNode.path("type_id").asText())); } catch (Exception ignored) {}
        }
        if (fileNode.hasNonNull("confidentiality_level_id")) {
            try { file.setConfidentialityLevelId(UUID.fromString(fileNode.path("confidentiality_level_id").asText())); } catch (Exception ignored) {}
        }
        file.setOriginalName(originalName);
        file.setStoredName(storedName);
        file.setBucketName(bucketName);
        file.setObjectKey(objectKey);
        file.setExtension(extension);
        file.setMimeType(mimeType);
        file.setSizeBytes(sizeBytes);
        file.setChecksumSha256(checksum);
        file.setStatus("ACTIVE");
        file.setSourceType("BRANCH_PACKAGE");
        file.setSourceReference(pkg.getPackageUuid());
        fileRepository.save(file);
    }

    @Transactional(readOnly = true)
    public List<PackageProject> getProjects(UUID packageId) {
        branchPackageRepository.findById(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("BranchPackage", "id", packageId));
        return packageProjectRepository.findByPackageId(packageId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOperations(UUID id) {
        BranchPackage pkg = branchPackageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BranchPackage", "id", id));

        Map<String, Object> operations = new LinkedHashMap<>();
        operations.put("packageId", pkg.getId());
        operations.put("packageUuid", pkg.getPackageUuid());
        operations.put("status", pkg.getStatus());
        operations.put("receivedAt", pkg.getReceivedAt());
        operations.put("verifiedAt", pkg.getVerifiedAt());
        operations.put("importedAt", pkg.getImportedAt());
        operations.put("errorMessage", pkg.getErrorMessage());

        long totalProjects = packageProjectRepository.countByPackageId(id);
        long importedProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.IMPORTED);
        long skippedProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.SKIPPED);
        long failedProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.FAILED);
        long duplicateProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.DUPLICATE);

        Map<String, Long> projectStats = new LinkedHashMap<>();
        projectStats.put("total", totalProjects);
        projectStats.put("imported", importedProjects);
        projectStats.put("skipped", skippedProjects);
        projectStats.put("failed", failedProjects);
        projectStats.put("duplicate", duplicateProjects);

        operations.put("projectStats", projectStats);
        operations.put("totalFilesCount", pkg.getFilesCount());

        return operations;
    }

    private void persistPackageProjects(UUID packageId, JsonNode projectsNode) {
        if (!projectsNode.isArray()) return;

        for (JsonNode projectNode : projectsNode) {
            try {
                PackageProject pp = new PackageProject();
                pp.setPackageId(packageId);
                pp.setProjectId(projectNode.path("project_id").asText());
                pp.setSourceProjectNumber(projectNode.path("project_number").asText());
                pp.setProjectName(projectNode.path("name").asText());
                pp.setProjectYear(projectNode.path("year").asInt(0));

                JsonNode filesNode = projectNode.path("files");
                int filesCount = filesNode.isArray() ? filesNode.size() : 0;
                pp.setFilesCount(filesCount);

                pp.setImportStatus(PackageProject.ImportStatus.SKIPPED);
                pp.setFilesImported(0);
                pp.setFilesFailed(0);

                packageProjectRepository.save(pp);
            } catch (Exception e) {
                log.error("Failed to persist package project record", e);
            }
        }
    }

    private String calculateSha256(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
