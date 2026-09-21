package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationService;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

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
    private final PackageArchiveService packageArchiveService;
    private final ImportOperationService importOperationService;

    public BranchPackageService(BranchPackageRepository branchPackageRepository,
                                PackageProjectRepository packageProjectRepository,
                                ObjectMapper objectMapper,
                                ProjectRepository projectRepository,
                                ProjectMemberRepository projectMemberRepository,
                                FileRepository fileRepository,
                                MinioService minioService,
                                MinioConfig minioConfig,
                                PackageArchiveService packageArchiveService,
                                ImportOperationService importOperationService) {
        this.branchPackageRepository = branchPackageRepository;
        this.packageProjectRepository = packageProjectRepository;
        this.objectMapper = objectMapper;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.fileRepository = fileRepository;
        this.minioService = minioService;
        this.minioConfig = minioConfig;
        this.packageArchiveService = packageArchiveService;
        this.importOperationService = importOperationService;
    }

    @Transactional(readOnly = true)
    public Page<PackageResponse> list(String searchTerm,
                                      BranchPackage.PackageStatus status,
                                      UUID sourceOrganizationId,
                                      int page,
                                      int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return branchPackageRepository.search(searchTerm, status, sourceOrganizationId, pageable)
                .map(PackageResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public PackageResponse getById(UUID id) {
        return PackageResponse.fromEntity(findPackage(id));
    }

    public PackageResponse receive(MultipartFile packageFile, UUID receivedBy) throws Exception {
        PackageArchiveService.Inspection inspection = packageArchiveService.inspect(packageFile);
        JsonNode manifest = inspection.manifest();

        UUID packageUuid = requiredUuid(manifest, "package_uuid");
        UUID sourceTerminalUuid = requiredUuid(manifest, "source_terminal_uuid");
        UUID sourceOrganizationId = optionalUuid(manifest, "source_organization_id");
        String sourceUsername = optionalText(manifest, "source_username");
        String schemaVersion = requiredText(manifest, "schema_version");

        if (!packageArchiveService.isSchemaSupported(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported schema_version: " + schemaVersion);
        }

        if (branchPackageRepository.existsByPackageUuid(packageUuid)) {
            throw new ConflictException("BranchPackage", "package_uuid", packageUuid);
        }
        if (branchPackageRepository.existsByPackageHash(inspection.packageHash())) {
            throw new ConflictException("BranchPackage", "package_hash", inspection.packageHash());
        }

        JsonNode projects = manifest.path("projects");
        if (!projects.isArray()) {
            throw new IllegalArgumentException("Manifest projects must be an array");
        }

        int projectsCount = projects.size();
        int filesCount = countFiles(projects);
        String packageName = packageFile.getOriginalFilename() != null
                ? packageFile.getOriginalFilename()
                : requiredText(manifest, "package_name");

        String bucket = minioConfig.getBucket();
        String packageObjectKey = packageObjectKey(packageUuid);
        boolean packageStored = false;

        try {
            minioService.uploadFile(packageFile, bucket, packageObjectKey);
            packageStored = true;

            BranchPackage pkg = new BranchPackage();
            pkg.setPackageUuid(packageUuid);
            pkg.setSourceOrganizationId(sourceOrganizationId);
            pkg.setSourceTerminalUuid(sourceTerminalUuid);
            pkg.setSourceUsername(sourceUsername);
            pkg.setPackageName(packageName);
            pkg.setPackageHash(inspection.packageHash());
            pkg.setManifestHash(inspection.manifestHash());
            pkg.setSchemaVersion(schemaVersion);
            pkg.setProjectsCount(projectsCount);
            pkg.setFilesCount(filesCount);
            pkg.setStatus(BranchPackage.PackageStatus.RECEIVED);
            pkg.setReceivedBy(receivedBy);
            pkg.setReceivedAt(LocalDateTime.now());
            pkg.setManifestData(inspection.manifestJson());

            BranchPackage saved = branchPackageRepository.save(pkg);
            log.info("Package received: id={}, packageUuid={}, projects={}, files={}, objectKey={}",
                    saved.getId(), packageUuid, projectsCount, filesCount, packageObjectKey);
            return PackageResponse.fromEntity(saved);
        } catch (Exception e) {
            if (packageStored) {
                try {
                    minioService.deleteFile(bucket, packageObjectKey);
                } catch (Exception cleanupError) {
                    log.error("Failed to remove orphan package object: {}", packageObjectKey, cleanupError);
                }
            }
            throw e;
        }
    }

    public PackageVerifyResponse verify(UUID id) {
        BranchPackage pkg = findPackage(id);

        if (pkg.getStatus() != BranchPackage.PackageStatus.RECEIVED
                && pkg.getStatus() != BranchPackage.PackageStatus.FAILED) {
            throw new IllegalStateException(
                    "Package can only be verified from RECEIVED or FAILED status; current status: " + pkg.getStatus());
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean packageIntegrityValid = false;
        boolean manifestHashValid = false;
        boolean schemaVersionValid = false;
        int projectsCount = 0;
        int filesCount = 0;
        long totalFileBytes = 0L;
        JsonNode verifiedManifest = null;

        String bucket = minioConfig.getBucket();
        String objectKey = packageObjectKey(pkg.getPackageUuid());

        try {
            String actualPackageHash;
            try (InputStream packageStream = minioService.downloadFile(bucket, objectKey)) {
                actualPackageHash = packageArchiveService.sha256(packageStream);
            }

            packageIntegrityValid = actualPackageHash.equalsIgnoreCase(pkg.getPackageHash());
            if (!packageIntegrityValid) {
                errors.add("Package hash mismatch");
            }

            PackageArchiveService.Verification verification;
            try (InputStream packageStream = minioService.downloadFile(bucket, objectKey)) {
                verification = packageArchiveService.verifyContents(
                        packageStream,
                        pkg.getManifestHash(),
                        pkg.getSchemaVersion()
                );
            }

            manifestHashValid = verification.manifestHashValid();
            schemaVersionValid = verification.schemaVersionValid();
            projectsCount = verification.projectsCount();
            filesCount = verification.filesCount();
            totalFileBytes = verification.verifiedFileBytes();
            verifiedManifest = verification.manifest();
            errors.addAll(verification.errors());
            warnings.addAll(verification.warnings());

            if (projectsCount != pkg.getProjectsCount()) {
                errors.add("Project count differs from received manifest metadata");
            }
            if (filesCount != pkg.getFilesCount()) {
                errors.add("File count differs from received manifest metadata");
            }

            boolean valid = packageIntegrityValid
                    && manifestHashValid
                    && schemaVersionValid
                    && verification.filesValid()
                    && errors.isEmpty();

            if (valid) {
                pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
                pkg.setVerifiedAt(LocalDateTime.now());
                pkg.setErrorMessage(null);
                if (verifiedManifest != null) {
                    pkg.setManifestData(objectMapper.writeValueAsString(verifiedManifest));
                }
            } else {
                pkg.setStatus(BranchPackage.PackageStatus.FAILED);
                pkg.setErrorMessage(String.join("; ", errors));
            }

            branchPackageRepository.save(pkg);

            return new PackageVerifyResponse(
                    pkg.getId(),
                    pkg.getPackageUuid().toString(),
                    valid,
                    manifestHashValid,
                    packageIntegrityValid,
                    schemaVersionValid,
                    projectsCount,
                    filesCount,
                    LocalDateTime.now(),
                    errors,
                    warnings,
                    buildProjectDetails(verifiedManifest),
                    totalFileBytes
            );
        } catch (Exception e) {
            log.error("Package verification failed: id={}", id, e);
            pkg.setStatus(BranchPackage.PackageStatus.FAILED);
            pkg.setErrorMessage(e.getMessage());
            branchPackageRepository.save(pkg);

            errors.add("Verification error: " + e.getMessage());
            return new PackageVerifyResponse(
                    pkg.getId(),
                    pkg.getPackageUuid().toString(),
                    false,
                    manifestHashValid,
                    packageIntegrityValid,
                    schemaVersionValid,
                    projectsCount,
                    filesCount,
                    LocalDateTime.now(),
                    errors,
                    warnings,
                    buildProjectDetails(verifiedManifest),
                    totalFileBytes
            );
        }
    }

    @Transactional(readOnly = true)
    public void assertImportable(UUID id) {
        BranchPackage pkg = findPackage(id);
        if (pkg.getStatus() != BranchPackage.PackageStatus.VERIFIED) {
            throw new IllegalStateException(
                    "Package must be VERIFIED before import; current status: " + pkg.getStatus());
        }
    }

    @Async
    public void importPackage(UUID id, PackageImportRequest request, UUID importedBy) {
        BranchPackage pkg = findPackage(id);
        if (pkg.getStatus() != BranchPackage.PackageStatus.VERIFIED) {
            log.warn("Package {} is not verified; import cancelled", id);
            return;
        }

        ImportOperation operation = importOperationService.create(id, importedBy);
        int importedProjects = 0;
        int failedProjects = 0;

        try {
            JsonNode manifest = objectMapper.readTree(pkg.getManifestData());
            JsonNode projects = manifest.path("projects");
            if (!projects.isArray()) {
                throw new IllegalStateException("Stored manifest has no projects array");
            }

            UUID targetOrgId = request.targetOrganizationId();
            pkg.setTargetOrganizationId(targetOrgId);

            for (JsonNode projectNode : projects) {
                try {
                    ImportProjectResult result = importProject(projectNode, pkg, request, importedBy);
                    if (result.imported()) {
                        importedProjects++;
                    }
                } catch (Exception e) {
                    failedProjects++;
                    log.error("Failed to import project from package {}", id, e);
                }
            }

            pkg.setImportedAt(LocalDateTime.now());

            if (failedProjects == 0) {
                pkg.setStatus(BranchPackage.PackageStatus.IMPORTED);
                pkg.setErrorMessage(null);
                importOperationService.updateStatus(operation.getId(), ImportOperation.Status.SUCCESS, null);
            } else if (importedProjects > 0) {
                pkg.setStatus(BranchPackage.PackageStatus.IMPORTED);
                pkg.setErrorMessage(failedProjects + " project(s) failed during import");
                importOperationService.updateStatus(
                        operation.getId(),
                        ImportOperation.Status.PARTIAL,
                        pkg.getErrorMessage()
                );
            } else {
                pkg.setStatus(BranchPackage.PackageStatus.FAILED);
                pkg.setErrorMessage("All projects failed to import");
                importOperationService.updateStatus(
                        operation.getId(),
                        ImportOperation.Status.FAILED,
                        pkg.getErrorMessage()
                );
            }

            branchPackageRepository.save(pkg);
            log.info("Package import completed: id={}, imported={}, failed={}",
                    id, importedProjects, failedProjects);
        } catch (Exception e) {
            log.error("Package import failed: id={}", id, e);
            pkg.setStatus(BranchPackage.PackageStatus.FAILED);
            pkg.setErrorMessage(e.getMessage());
            branchPackageRepository.save(pkg);
            importOperationService.updateStatus(operation.getId(), ImportOperation.Status.FAILED, e.getMessage());
        }
    }

    private ImportProjectResult importProject(JsonNode projectNode,
                                              BranchPackage pkg,
                                              PackageImportRequest request,
                                              UUID importedBy) throws Exception {
        UUID sourceProjectUuid = requiredUuid(projectNode, "project_id");
        String projectNumber = requiredText(projectNode, "project_number");
        int year = projectNode.path("year").asInt(0);
        if (year < 1900) {
            throw new IllegalArgumentException("Invalid project year for " + sourceProjectUuid);
        }

        UUID resolvedOrgId = resolveOrganization(projectNode, pkg, request);
        if (resolvedOrgId == null) {
            throw new IllegalArgumentException("No target organization mapping for project " + sourceProjectUuid);
        }

        var existing = projectRepository.findActiveByProjectNumberAndOrganizationIdAndYear(
                projectNumber,
                resolvedOrgId,
                year
        );

        if (existing.isPresent()) {
            PackageProject link = createPackageProjectLink(
                    pkg,
                    existing.get(),
                    sourceProjectUuid,
                    projectNode,
                    request.skipDuplicates()
                            ? PackageProject.ImportStatus.SKIPPED
                            : PackageProject.ImportStatus.DUPLICATE
            );

            if (request.skipDuplicates()) {
                packageProjectRepository.save(link);
                log.info("Skipped duplicate imported project: source={}, existing={}",
                        sourceProjectUuid, existing.get().getId());
                return new ImportProjectResult(false, existing.get().getId());
            }

            packageProjectRepository.save(link);
            throw new IllegalStateException(
                    "Project already exists for organization/year/number: " + projectNumber);
        }

        Project project = buildProject(projectNode, resolvedOrgId, pkg, importedBy);
        Project savedProject = projectRepository.save(project);
        createProjectMembers(projectNode, savedProject.getId());

        int totalFiles = 0;
        int importedFiles = 0;
        int failedFiles = 0;

        JsonNode files = projectNode.path("files");
        if (files.isArray()) {
            for (JsonNode fileNode : files) {
                totalFiles++;
                try {
                    importFile(fileNode, savedProject.getId(), pkg, importedBy);
                    importedFiles++;
                } catch (Exception e) {
                    failedFiles++;
                    log.error("Failed to import file for project {}", savedProject.getId(), e);
                }
            }
        }

        PackageProject link = createPackageProjectLink(
                pkg,
                savedProject,
                sourceProjectUuid,
                projectNode,
                failedFiles == 0
                        ? PackageProject.ImportStatus.IMPORTED
                        : PackageProject.ImportStatus.FAILED
        );
        link.setFilesCount(totalFiles);
        link.setFilesImported(importedFiles);
        link.setFilesFailed(failedFiles);
        if (failedFiles > 0) {
            link.setImportError(failedFiles + " file(s) failed to import");
        }
        packageProjectRepository.save(link);

        if (failedFiles > 0) {
            throw new IllegalStateException(
                    "Project " + savedProject.getId() + " imported with file failures");
        }

        return new ImportProjectResult(true, savedProject.getId());
    }

    private Project buildProject(JsonNode projectNode,
                                 UUID organizationId,
                                 BranchPackage pkg,
                                 UUID createdBy) {
        Project project = new Project();
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setOrganizationId(organizationId);
        project.setProjectNumber(requiredText(projectNode, "project_number"));
        project.setName(projectNode.path("name").asText("Imported Project"));
        project.setYear(projectNode.path("year").asInt());
        project.setProjectCode(optionalText(projectNode, "project_code"));
        project.setExecutingEntity(optionalText(projectNode, "executing_entity"));
        project.setSupervisorName(optionalText(projectNode, "supervisor_name"));
        project.setDescription(optionalText(projectNode, "description"));
        project.setNotes(optionalText(projectNode, "notes"));
        project.setTableOfContents(optionalText(projectNode, "table_of_contents"));
        project.setKeywords(optionalText(projectNode, "keywords"));
        project.setProjectTypeId(optionalUuid(projectNode, "project_type_id"));
        project.setConfidentialityLevelId(optionalUuid(projectNode, "confidentiality_level_id"));
        project.setStatus(Project.ProjectStatus.IMPORTED);
        project.setDataCompletionStatus(Project.DataCompletionStatus.COMPLETE);
        project.setSourceType(Project.SourceType.BRANCH_PACKAGE);
        project.setSourceReference(pkg.getPackageUuid().toString());

        // Project.createdBy is still a String in the current model. Store the authenticated
        // central user UUID as text until the actor-column UUID migration is completed.
        project.setCreatedBy(createdBy);
        return project;
    }

    private void createProjectMembers(JsonNode projectNode, UUID projectId) {
        JsonNode members = projectNode.path("members");
        if (!members.isArray()) {
            return;
        }

        for (JsonNode memberNode : members) {
            ProjectMember member = new ProjectMember();
            member.setProjectId(projectId);
            member.setPersonName(
                    memberNode.path("person_name").asText(
                            memberNode.path("name").asText("Unknown")
                    )
            );
            member.setOrganizationName(optionalText(memberNode, "organization_name"));
            member.setRoleName(optionalText(memberNode, "role_name"));
            member.setNotes(optionalText(memberNode, "notes"));
            projectMemberRepository.save(member);
        }
    }

    private void importFile(JsonNode fileNode,
                            UUID projectId,
                            BranchPackage pkg,
                            UUID uploadedBy) throws Exception {
        String originalName = fileNode.path("original_name").asText(
                fileNode.path("name").asText("file"));
        String extension = optionalText(fileNode, "extension");
        String mimeType = fileNode.path("mime_type").asText("application/octet-stream");
        long expectedSize = fileNode.path("size_bytes").asLong(-1);
        String expectedChecksum = requiredText(fileNode, "checksum_sha256");
        String sourceEntryPath = packageArchiveService.resolveFileEntryPath(fileNode);

        String storedName = UUID.randomUUID() + (extension != null ? "." + extension : "");
        String bucket = minioConfig.getBucket();
        String objectKey = "import/" + pkg.getPackageUuid() + "/" + projectId + "/" + storedName;

        try (InputStream packageStream = minioService.downloadFile(
                bucket,
                packageObjectKey(pkg.getPackageUuid()))) {

            packageArchiveService.withEntry(packageStream, sourceEntryPath, (entryStream, declaredSize) -> {
                long uploadSize = expectedSize >= 0 ? expectedSize : declaredSize;
                if (uploadSize < 0) {
                    throw new IllegalArgumentException(
                            "Package file size is missing for entry: " + sourceEntryPath);
                }
                minioService.uploadRaw(entryStream, bucket, objectKey, mimeType, uploadSize);
            });
        }

        FileEntity file = new FileEntity();
        file.setProjectId(projectId);
        file.setCategoryId(optionalUuid(fileNode, "category_id"));
        file.setTypeId(optionalUuid(fileNode, "type_id"));
        file.setConfidentialityLevelId(optionalUuid(fileNode, "confidentiality_level_id"));
        file.setOriginalName(originalName);
        file.setStoredName(storedName);
        file.setBucketName(bucket);
        file.setObjectKey(objectKey);
        file.setExtension(extension);
        file.setMimeType(mimeType);
        file.setSizeBytes(expectedSize >= 0 ? expectedSize : null);
        file.setChecksumSha256(expectedChecksum);
        file.setStatus("ACTIVE");
        file.setUploadedBy(uploadedBy);
        file.setSourceType("BRANCH_PACKAGE");
        file.setSourceReference(pkg.getPackageUuid().toString());
        fileRepository.save(file);
    }

    private PackageProject createPackageProjectLink(BranchPackage pkg,
                                                    Project centralProject,
                                                    UUID sourceProjectUuid,
                                                    JsonNode projectNode,
                                                    PackageProject.ImportStatus status) {
        PackageProject link = new PackageProject();
        link.setPackageId(pkg.getId());
        link.setProjectId(centralProject.getId());
        link.setLocalProjectUuid(sourceProjectUuid);
        link.setSourceProjectNumber(projectNode.path("project_number").asText(null));
        link.setProjectName(projectNode.path("name").asText(null));
        link.setProjectYear(projectNode.path("year").asInt(0));
        link.setImportStatus(status);

        JsonNode files = projectNode.path("files");
        link.setFilesCount(files.isArray() ? files.size() : 0);
        link.setFilesImported(0);
        link.setFilesFailed(0);
        return link;
    }

    private UUID resolveOrganization(JsonNode projectNode,
                                     BranchPackage pkg,
                                     PackageImportRequest request) {
        Map<UUID, UUID> mapping = request.organizationMapping() != null
                ? request.organizationMapping()
                : Map.of();

        UUID projectSourceOrg = optionalUuid(projectNode, "organization_id");
        if (projectSourceOrg != null && mapping.containsKey(projectSourceOrg)) {
            return mapping.get(projectSourceOrg);
        }

        if (pkg.getSourceOrganizationId() != null
                && mapping.containsKey(pkg.getSourceOrganizationId())) {
            return mapping.get(pkg.getSourceOrganizationId());
        }

        return request.targetOrganizationId();
    }

    @Transactional(readOnly = true)
    public List<PackageProject> getProjects(UUID packageId) {
        findPackage(packageId);
        return packageProjectRepository.findByPackageId(packageId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOperations(UUID id) {
        BranchPackage pkg = findPackage(id);

        Map<String, Object> operations = new LinkedHashMap<>();
        operations.put("packageId", pkg.getId());
        operations.put("packageUuid", pkg.getPackageUuid());
        operations.put("status", pkg.getStatus());
        operations.put("receivedAt", pkg.getReceivedAt());
        operations.put("verifiedAt", pkg.getVerifiedAt());
        operations.put("importedAt", pkg.getImportedAt());
        operations.put("errorMessage", pkg.getErrorMessage());
        operations.put("importOperations", importOperationService.getByPackageId(id));

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
        operations.put("manifestProjectsCount", pkg.getProjectsCount());
        operations.put("totalFilesCount", pkg.getFilesCount());

        return operations;
    }

    private List<PackageVerifyResponse.ProjectManifestDetail> buildProjectDetails(JsonNode manifest) {
        if (manifest == null || !manifest.path("projects").isArray()) {
            return List.of();
        }

        List<PackageVerifyResponse.ProjectManifestDetail> details = new ArrayList<>();
        for (JsonNode project : manifest.path("projects")) {
            String projectId = project.path("project_id").asText(null);
            int filesCount = project.path("files").isArray()
                    ? project.path("files").size()
                    : 0;

            details.add(new PackageVerifyResponse.ProjectManifestDetail(
                    projectId,
                    project.path("project_number").asText(null),
                    project.path("name").asText(null),
                    project.hasNonNull("year") ? project.path("year").asInt() : null,
                    filesCount,
                    false,
                    null
            ));
        }
        return details;
    }

    private BranchPackage findPackage(UUID id) {
        return branchPackageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BranchPackage", "id", id));
    }

    private String packageObjectKey(UUID packageUuid) {
        return "packages/" + packageUuid + "/original.pkg";
    }

    private int countFiles(JsonNode projects) {
        int filesCount = 0;
        for (JsonNode project : projects) {
            JsonNode files = project.path("files");
            if (files.isArray()) {
                filesCount += files.size();
            }
        }
        return filesCount;
    }

    private UUID requiredUuid(JsonNode node, String field) {
        String value = requiredText(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID in " + field + ": " + value);
        }
    }

    private UUID optionalUuid(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID in " + field + ": " + value);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private record ImportProjectResult(boolean imported, UUID centralProjectId) {}
}
