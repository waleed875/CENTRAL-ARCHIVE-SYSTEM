package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationService;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageImportRequest;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageResponse;
import com.centralArchiveSystem.Archive.import_.package_.dto.PackageVerifyResponse;
import com.centralArchiveSystem.Archive.project.file.FileContentInspector;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileType;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectType;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectTypeRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final OrganizationRepository organizationRepository;
    private final ProjectTypeRepository projectTypeRepository;
    private final FileCategoryRepository fileCategoryRepository;
    private final FileTypeRepository fileTypeRepository;
    private final ConfidentialityAccessService confidentialityAccessService;
    private final TransactionTemplate projectImportTransaction;

    public BranchPackageService(BranchPackageRepository branchPackageRepository,
                                PackageProjectRepository packageProjectRepository,
                                ObjectMapper objectMapper,
                                ProjectRepository projectRepository,
                                ProjectMemberRepository projectMemberRepository,
                                FileRepository fileRepository,
                                MinioService minioService,
                                MinioConfig minioConfig,
                                PackageArchiveService packageArchiveService,
                                ImportOperationService importOperationService,
                                OrganizationRepository organizationRepository,
                                ProjectTypeRepository projectTypeRepository,
                                FileCategoryRepository fileCategoryRepository,
                                FileTypeRepository fileTypeRepository,
                                ConfidentialityAccessService confidentialityAccessService,
                                PlatformTransactionManager transactionManager) {
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
        this.organizationRepository = organizationRepository;
        this.projectTypeRepository = projectTypeRepository;
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileTypeRepository = fileTypeRepository;
        this.confidentialityAccessService = confidentialityAccessService;
        this.projectImportTransaction = new TransactionTemplate(transactionManager);
        this.projectImportTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            registerRollbackObjectCleanup(bucket, packageObjectKey);

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
    public void assertImportable(UUID id, PackageImportRequest request) {
        BranchPackage pkg = findPackage(id);
        if (pkg.getStatus() != BranchPackage.PackageStatus.VERIFIED) {
            throw new IllegalStateException(
                    "Package must be VERIFIED before import; current status: " + pkg.getStatus());
        }
        if (request == null) {
            throw new IllegalArgumentException("Package import request is required");
        }

        try {
            JsonNode manifest = objectMapper.readTree(pkg.getManifestData());
            JsonNode projects = manifest.path("projects");
            if (!projects.isArray()) {
                throw new IllegalArgumentException("Stored manifest has no projects array");
            }

            Map<UUID, UUID> mapping = request.organizationMapping() != null
                    ? request.organizationMapping()
                    : Map.of();

            for (JsonNode projectNode : projects) {
                UUID sourceOrgId = sourceOrganizationId(projectNode, pkg);
                if (sourceOrgId == null) {
                    throw new IllegalArgumentException(
                            "Project has no source organization that can be mapped");
                }

                UUID targetOrgId = mapping.get(sourceOrgId);
                if (targetOrgId == null) {
                    throw new IllegalArgumentException(
                            "Missing organization mapping for source organization: " + sourceOrgId);
                }

                Organization targetOrganization = organizationRepository.findById(targetOrgId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Target organization does not exist: " + targetOrgId));
                if (!Boolean.TRUE.equals(targetOrganization.getIsActive())) {
                    throw new IllegalArgumentException(
                            "Target organization is inactive: " + targetOrgId);
                }

                UUID projectTypeId = optionalUuid(projectNode, "project_type_id");
                if (projectTypeId != null) {
                    ProjectType projectType = projectTypeRepository.findById(projectTypeId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Project type does not exist: " + projectTypeId));
                    if (!Boolean.TRUE.equals(projectType.getIsActive())) {
                        throw new IllegalArgumentException(
                                "Project type is inactive: " + projectTypeId);
                    }
                }

                UUID projectLevelId = optionalUuid(projectNode, "confidentiality_level_id");
                if (projectLevelId == null) {
                    projectLevelId = request.confidentialityLevelId();
                }
                confidentialityAccessService.requireReadAccess(projectLevelId);

                JsonNode files = projectNode.path("files");
                if (files.isArray()) {
                    for (JsonNode fileNode : files) {
                        UUID categoryId = optionalUuid(fileNode, "category_id");
                        FileCategory category = null;
                        if (categoryId != null) {
                            category = fileCategoryRepository.findById(categoryId)
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "File category does not exist: " + categoryId));
                            if (!Boolean.TRUE.equals(category.getIsActive())) {
                                throw new IllegalArgumentException(
                                        "File category is inactive: " + categoryId);
                            }
                        }

                        UUID typeId = optionalUuid(fileNode, "type_id");
                        if (typeId != null) {
                            FileType fileType = fileTypeRepository.findById(typeId)
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "File type does not exist: " + typeId));
                            if (!Boolean.TRUE.equals(fileType.getIsActive())) {
                                throw new IllegalArgumentException(
                                        "File type is inactive: " + typeId);
                            }
                            if (categoryId == null) {
                                throw new IllegalArgumentException(
                                        "File type requires category_id: " + typeId);
                            }
                            if (!categoryId.equals(fileType.getCategoryId())) {
                                throw new IllegalArgumentException(
                                        "File type does not belong to category: " + typeId);
                            }
                        }

                        UUID fileLevelId = optionalUuid(fileNode, "confidentiality_level_id");
                        if (fileLevelId == null) {
                            fileLevelId = projectLevelId;
                        }
                        confidentialityAccessService.requireReadAccess(fileLevelId);
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Stored package manifest is invalid", e);
        }
    }

    @Async
    public void importPackage(UUID id,
                              PackageImportRequest request,
                              UUID importedBy,
                              UUID operationId) {
        ImportOperation operation = importOperationService.getById(operationId);
        if (!java.util.Objects.equals(operation.getPackageId(), id)
                || !java.util.Objects.equals(operation.getImportedBy(), importedBy)
                || operation.getStatus() != ImportOperation.Status.STARTED) {
            throw new IllegalStateException(
                    "Import operation does not match package/import actor or is not active");
        }

        BranchPackage pkg;
        try {
            pkg = findPackage(id);
        } catch (Exception e) {
            importOperationService.updateStatus(
                    operationId,
                    ImportOperation.Status.FAILED,
                    e.getMessage()
            );
            throw e;
        }

        if (pkg.getStatus() != BranchPackage.PackageStatus.VERIFIED) {
            String error = "Package must remain VERIFIED until import starts; current status: "
                    + pkg.getStatus();
            log.warn("Package {} import cancelled: {}", id, error);
            importOperationService.updateStatus(
                    operationId,
                    ImportOperation.Status.FAILED,
                    error
            );
            return;
        }

        int importedProjects = 0;
        int partialProjects = 0;
        int failedProjects = 0;

        try {
            importOperationService.heartbeat(operationId);
            JsonNode manifest = revalidateStoredPackageForImport(pkg);
            JsonNode projects = manifest.path("projects");
            if (!projects.isArray()) {
                throw new IllegalStateException("Stored manifest has no projects array");
            }

            for (JsonNode projectNode : projects) {
                importOperationService.heartbeat(operationId);
                List<StoredObject> projectObjects = new ArrayList<>();
                try {
                    ImportProjectResult result = projectImportTransaction.execute(status -> {
                        try {
                            return importProject(
                                    projectNode,
                                    pkg,
                                    request,
                                    importedBy,
                                    projectObjects
                            );
                        } catch (Exception e) {
                            throw new ProjectImportException(e);
                        }
                    });

                    if (result == null) {
                        throw new IllegalStateException("Project import transaction returned no result");
                    }
                    if (result.imported()) {
                        importedProjects++;
                    }
                    if (result.partial()) {
                        partialProjects++;
                    }
                } catch (Exception e) {
                    failedProjects++;
                    cleanupImportedObjects(projectObjects);
                    log.error("Failed to import project from package {}", id, e);
                } finally {
                    importOperationService.heartbeat(operationId);
                }
            }

            if (importedProjects > 0) {
                pkg.setImportedAt(LocalDateTime.now());
            }

            if (failedProjects == 0 && partialProjects == 0) {
                pkg.setStatus(BranchPackage.PackageStatus.IMPORTED);
                pkg.setErrorMessage(null);
                importOperationService.updateStatus(operation.getId(), ImportOperation.Status.SUCCESS, null);
            } else if (importedProjects > 0) {
                pkg.setStatus(BranchPackage.PackageStatus.IMPORTED);
                pkg.setErrorMessage(
                        partialProjects + " project(s) imported incompletely; "
                                + failedProjects + " project(s) failed before import"
                );
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
            log.info("Package import completed: id={}, imported={}, partial={}, failed={}",
                    id, importedProjects, partialProjects, failedProjects);
        } catch (Exception e) {
            log.error("Package import failed: id={}", id, e);
            pkg.setStatus(BranchPackage.PackageStatus.FAILED);
            pkg.setErrorMessage(e.getMessage());
            branchPackageRepository.save(pkg);
            importOperationService.updateStatus(operation.getId(), ImportOperation.Status.FAILED, e.getMessage());
        }
    }

    private JsonNode revalidateStoredPackageForImport(BranchPackage pkg) throws Exception {
        String bucket = minioConfig.getBucket();
        String objectKey = packageObjectKey(pkg.getPackageUuid());

        String actualPackageHash;
        try (InputStream packageStream = minioService.downloadFile(bucket, objectKey)) {
            actualPackageHash = packageArchiveService.sha256(packageStream);
        }

        if (!actualPackageHash.equalsIgnoreCase(pkg.getPackageHash())) {
            throw new IllegalStateException("Package hash changed after verification");
        }

        PackageArchiveService.Verification verification;
        try (InputStream packageStream = minioService.downloadFile(bucket, objectKey)) {
            verification = packageArchiveService.verifyContents(
                    packageStream,
                    pkg.getManifestHash(),
                    pkg.getSchemaVersion()
            );
        }

        if (!verification.manifestHashValid()
                || !verification.schemaVersionValid()
                || !verification.filesValid()
                || !verification.errors().isEmpty()
                || verification.manifest() == null) {
            String details = verification.errors().isEmpty()
                    ? "stored package verification failed"
                    : String.join("; ", verification.errors());
            throw new IllegalStateException(
                    "Package integrity verification failed before import: " + details);
        }

        return verification.manifest();
    }

    private ImportProjectResult importProject(JsonNode projectNode,
                                              BranchPackage pkg,
                                              PackageImportRequest request,
                                              UUID importedBy,
                                              List<StoredObject> projectObjects) throws Exception {
        UUID sourceProjectUuid = requiredUuid(projectNode, "project_id");

        var priorPackageProject = packageProjectRepository.findByPackageIdAndLocalProjectUuid(
                pkg.getId(), sourceProjectUuid);
        if (priorPackageProject.isPresent()) {
            PackageProject prior = priorPackageProject.get();
            boolean partial = prior.getFilesFailed() != null && prior.getFilesFailed() > 0;
            log.info(
                    "Skipping already processed project during import retry: package={}, sourceProject={}, centralProject={}",
                    pkg.getId(), sourceProjectUuid, prior.getProjectId());
            return new ImportProjectResult(false, partial, prior.getProjectId());
        }

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
                    PackageProject.ImportStatus.SKIPPED
            );
            packageProjectRepository.save(link);
            log.info("Skipped duplicate imported project: source={}, existing={}",
                    sourceProjectUuid, existing.get().getId());
            return new ImportProjectResult(false, false, existing.get().getId());
        }

        Project project = buildProject(projectNode, resolvedOrgId, pkg, request, importedBy);
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
                    StoredObject storedObject = importFile(
                            fileNode,
                            savedProject.getId(),
                            savedProject.getConfidentialityLevelId(),
                            pkg,
                            importedBy
                    );
                    projectObjects.add(storedObject);
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
                PackageProject.ImportStatus.IMPORTED
        );
        link.setFilesCount(totalFiles);
        link.setFilesImported(importedFiles);
        link.setFilesFailed(failedFiles);
        if (failedFiles > 0) {
            link.setImportError(failedFiles + " file(s) failed to import");
        }
        packageProjectRepository.save(link);

        if (failedFiles > 0) {
            savedProject.setDataCompletionStatus(Project.DataCompletionStatus.INCOMPLETE);
            projectRepository.save(savedProject);
            log.warn("Project {} imported incompletely: {} file(s) failed",
                    savedProject.getId(), failedFiles);
        }

        return new ImportProjectResult(
                true,
                failedFiles > 0,
                savedProject.getId()
        );
    }

    private Project buildProject(JsonNode projectNode,
                                 UUID organizationId,
                                 BranchPackage pkg,
                                 PackageImportRequest request,
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
        UUID projectLevelId = optionalUuid(projectNode, "confidentiality_level_id");
        if (projectLevelId == null) {
            projectLevelId = request.confidentialityLevelId();
        }
        project.setConfidentialityLevelId(projectLevelId);
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

    private StoredObject importFile(JsonNode fileNode,
                                    UUID projectId,
                                    UUID projectConfidentialityLevelId,
                                    BranchPackage pkg,
                                    UUID uploadedBy) throws Exception {
        String originalName = fileNode.path("original_name").asText(
                fileNode.path("name").asText("file"));
        String extension = optionalText(fileNode, "extension");
        if (extension == null) {
            extension = FileContentInspector.extensionFromName(originalName);
        }
        if (extension == null) {
            throw new IllegalArgumentException(
                    "Imported file has no resolvable extension: " + originalName);
        }
        String mimeType = FileContentInspector.mimeTypeForExtension(extension);
        long expectedSize = fileNode.path("size_bytes").asLong(-1);
        String expectedChecksum = requiredText(fileNode, "checksum_sha256");
        String sourceEntryPath = packageArchiveService.resolveFileEntryPath(fileNode);

        String storedName = UUID.randomUUID() + (extension != null ? "." + extension : "");
        String bucket = minioConfig.getBucket();
        String objectKey = "import/" + pkg.getPackageUuid() + "/" + projectId + "/" + storedName;

        boolean uploadedToObjectStorage = false;
        try {
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
            uploadedToObjectStorage = true;

            FileEntity file = new FileEntity();
            file.setProjectId(projectId);
            file.setCategoryId(optionalUuid(fileNode, "category_id"));
            file.setTypeId(optionalUuid(fileNode, "type_id"));
            UUID fileLevelId = optionalUuid(fileNode, "confidentiality_level_id");
            if (fileLevelId == null) {
                fileLevelId = projectConfidentialityLevelId;
            }
            file.setConfidentialityLevelId(fileLevelId);
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
            return new StoredObject(bucket, objectKey);
        } catch (Exception e) {
            if (uploadedToObjectStorage) {
                try {
                    minioService.deleteFile(bucket, objectKey);
                } catch (Exception cleanupError) {
                    log.error("Failed to remove orphan imported object: {}", objectKey, cleanupError);
                }
            }
            throw e;
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
                                    "Failed to remove MinIO object after transaction rollback: {}",
                                    objectKey,
                                    cleanupError
                            );
                        }
                    }
                }
        );
    }

    private void cleanupImportedObjects(List<StoredObject> objects) {
        for (int i = objects.size() - 1; i >= 0; i--) {
            StoredObject object = objects.get(i);
            try {
                minioService.deleteFile(object.bucket(), object.objectKey());
            } catch (Exception cleanupError) {
                log.error(
                        "Failed to remove MinIO object after project import rollback: {}",
                        object.objectKey(),
                        cleanupError
                );
            }
        }
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

        UUID sourceOrgId = sourceOrganizationId(projectNode, pkg);
        return sourceOrgId != null ? mapping.get(sourceOrgId) : null;
    }

    private UUID sourceOrganizationId(JsonNode projectNode, BranchPackage pkg) {
        UUID projectSourceOrg = optionalUuid(projectNode, "organization_id");
        return projectSourceOrg != null ? projectSourceOrg : pkg.getSourceOrganizationId();
    }

    @Transactional(readOnly = true)
    public List<PackageProject> getProjects(UUID packageId) {
        findPackage(packageId);

        return packageProjectRepository.findByPackageId(packageId).stream()
                .filter(link -> projectRepository.findById(link.getProjectId())
                        .map(project -> confidentialityAccessService.canRead(
                                project.getConfidentialityLevelId()))
                        .orElse(false))
                .toList();
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

    private record StoredObject(String bucket, String objectKey) {}

    private static final class ProjectImportException extends RuntimeException {
        private ProjectImportException(Throwable cause) {
            super(cause);
        }
    }

    private record ImportProjectResult(
            boolean imported,
            boolean partial,
            UUID centralProjectId
    ) {}
}
