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
import com.centralArchiveSystem.Archive.import_.package_.dto.PackagePreviewResponse;
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
import java.util.LinkedHashSet;
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
    private final SealedPackageInspector sealedPackageInspector;
    private final SealedPackageDecryptor sealedPackageDecryptor;
    private final DecryptedPayloadValidator decryptedPayloadValidator;
    private final SealedPayloadCanonicalizer sealedPayloadCanonicalizer;
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
                                SealedPackageInspector sealedPackageInspector,
                                SealedPackageDecryptor sealedPackageDecryptor,
                                DecryptedPayloadValidator decryptedPayloadValidator,
                                SealedPayloadCanonicalizer sealedPayloadCanonicalizer,
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
        this.sealedPackageInspector = sealedPackageInspector;
        this.sealedPackageDecryptor = sealedPackageDecryptor;
        this.decryptedPayloadValidator = decryptedPayloadValidator;
        this.sealedPayloadCanonicalizer = sealedPayloadCanonicalizer;
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

    /** Safe inspection of stored, verified manifests; no import or file content access. */
    @Transactional(readOnly = true)
    public PackagePreviewResponse preview(UUID id) {
        BranchPackage pkg = findPackage(id);
        if (pkg.getStatus() != BranchPackage.PackageStatus.VERIFIED
                && pkg.getStatus() != BranchPackage.PackageStatus.IMPORTED) {
            throw new IllegalStateException("Package must be verified before preview");
        }
        if (pkg.getManifestData() == null) {
            throw new IllegalStateException("Verified package manifest is not available");
        }
        try {
            JsonNode manifest = objectMapper.readTree(pkg.getManifestData());
            JsonNode sources = manifest.path("projects");
            if (!sources.isArray()) throw new IllegalArgumentException("Invalid package manifest");
            List<PackagePreviewResponse.ProjectEntry> summaries = new ArrayList<>();
            int totalFiles = 0;
            boolean truncated = sources.size() > 100;
            for (JsonNode project : sources) {
                if (summaries.size() >= 100) break;
                JsonNode files = project.path("files");
                List<PackagePreviewResponse.FileEntry> entries = new ArrayList<>();
                int projectCount = files.isArray() ? files.size() : 0;
                totalFiles += projectCount;
                if (files.isArray()) for (JsonNode file : files) {
                    if (entries.size() >= 250) break;
                    entries.add(new PackagePreviewResponse.FileEntry(
                            bounded(file.path("original_name").asText(file.path("name").asText("file")), 250),
                            bounded(file.path("extension").asText(""), 20),
                            file.path("size_bytes").canConvertToLong()
                                    ? file.path("size_bytes").asLong() : null));
                }
                boolean limitedFiles = projectCount > entries.size();
                truncated |= limitedFiles;
                summaries.add(new PackagePreviewResponse.ProjectEntry(
                        bounded(project.path("project_id").asText(""), 60),
                        bounded(project.path("project_number").asText(""), 80),
                        bounded(project.path("name").asText(""), 250),
                        project.path("year").canConvertToInt() ? project.path("year").asInt() : null,
                        project.path("version_no").canConvertToInt() ? project.path("version_no").asInt() : null,
                        bounded(project.path("project_uid").asText(""), 60),
                        bounded(project.path("description").asText(""), 2000),
                        projectCount, limitedFiles, entries));
            }
            return new PackagePreviewResponse(pkg.getId(), pkg.getPackageName(),
                    pkg.getStatus().name(), pkg.getSchemaVersion(), pkg.getPackageFormat(),
                    sources.size(), pkg.getFilesCount(), truncated, summaries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Stored package manifest is invalid", e);
        }
    }

    private static String bounded(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    public PackageResponse receive(MultipartFile packageFile, UUID receivedBy) throws Exception {
        return receive(packageFile, null, receivedBy);
    }

    public PackageResponse receive(MultipartFile packageFile,
                                   String passphrase,
                                   UUID receivedBy) throws Exception {
        String originalName = packageFile != null ? packageFile.getOriginalFilename() : null;
        if (sealedPackageInspector.supportsFilename(originalName)) {
            return receiveSealed(packageFile, passphrase, receivedBy);
        }
        return receivePlain(packageFile, receivedBy);
    }

    private PackageResponse receivePlain(MultipartFile packageFile, UUID receivedBy) throws Exception {
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
        rejectDuplicatePackage(packageUuid, inspection.packageHash());

        JsonNode projects = manifest.path("projects");
        if (!projects.isArray()) {
            throw new IllegalArgumentException("Manifest projects must be an array");
        }

        BranchPackage pkg = basePackage(
                packageUuid,
                sourceOrganizationId,
                sourceTerminalUuid,
                sourceUsername,
                packageFile.getOriginalFilename() != null
                        ? packageFile.getOriginalFilename()
                        : requiredText(manifest, "package_name"),
                inspection.packageHash(),
                schemaVersion,
                projects.size(),
                countFiles(projects),
                receivedBy
        );
        pkg.setManifestHash(inspection.manifestHash());
        pkg.setManifestData(inspection.manifestJson());
        pkg.setPackageFormat("central-archive/pkg");
        pkg.setEncrypted(false);
        pkg.setStatus(BranchPackage.PackageStatus.RECEIVED);
        return storeReceivedPackage(packageFile, pkg);
    }

    private PackageResponse receiveSealed(MultipartFile packageFile,
                                                String passphrase,
                                                UUID receivedBy) throws Exception {
        SealedPackageInspector.Inspection inspection = sealedPackageInspector.inspect(packageFile);

        if (!packageArchiveService.isSchemaSupported(inspection.schemaVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported schema_version: " + inspection.schemaVersion());
        }
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalArgumentException(
                    "passphrase is required for encrypted .zip/.nxpkg packages");
        }

        rejectDuplicatePackage(inspection.packageUuid(), inspection.packageHash());

        try (SealedPackageDecryptor.DecryptedPayload decrypted =
                     sealedPackageDecryptor.decrypt(packageFile, inspection, passphrase);
             SealedPayloadCanonicalizer.CanonicalPackage canonical =
                     sealedPayloadCanonicalizer.canonicalize(decrypted.path(), inspection.header())) {

            PackageArchiveService.Verification verification;
            try (InputStream input = java.nio.file.Files.newInputStream(canonical.path())) {
                verification = packageArchiveService.verifyContents(
                        input,
                        canonical.manifestHash(),
                        inspection.schemaVersion()
                );
            }

            List<String> verificationErrors = new ArrayList<>(verification.errors());
            if (!verification.manifestHashValid()) {
                verificationErrors.add("Canonical manifest hash mismatch");
            }
            if (!verification.schemaVersionValid()) {
                verificationErrors.add("Canonical schema_version is invalid");
            }
            if (!verification.filesValid()) {
                verificationErrors.add("Canonical package file verification failed");
            }
            if (canonical.projectsCount() != inspection.projectsCount()) {
                verificationErrors.add(
                        "Canonical project count differs from signed header metadata");
            }
            if (canonical.filesCount() != inspection.filesCount()) {
                verificationErrors.add(
                        "Canonical file count differs from signed header metadata");
            }
            if (!verificationErrors.isEmpty()) {
                throw new IllegalArgumentException(
                        "Decrypted package verification failed: "
                                + String.join("; ", verificationErrors));
            }

            BranchPackage pkg = basePackage(
                    inspection.packageUuid(),
                    inspection.sourceOrganizationId(),
                    inspection.sourceTerminalUuid(),
                    inspection.sourceUsername(),
                    inspection.packageName() != null
                            ? inspection.packageName()
                            : packageFile.getOriginalFilename(),
                    inspection.packageHash(),
                    inspection.schemaVersion(),
                    canonical.projectsCount(),
                    canonical.filesCount(),
                    receivedBy
            );
            pkg.setManifestHash(canonical.manifestHash());
            pkg.setManifestData(canonical.manifestJson());
            pkg.setPackageFormat(inspection.format());
            pkg.setPackageVersion(inspection.version());
            pkg.setEncrypted(true);
            pkg.setHeaderHash(inspection.headerHash());
            pkg.setPayloadHash(inspection.payloadHash());
            pkg.setSignatureValid(inspection.signatureValid());
            pkg.setTerminalFingerprint(inspection.terminalFingerprint());
            pkg.setHeaderData(inspection.headerJson());
            pkg.setPayloadSizeBytes(inspection.payloadSizeBytes());
            pkg.setDecryptedPayloadHash(decrypted.sha256());
            pkg.setDecryptedPayloadSizeBytes(decrypted.sizeBytes());
            pkg.setStatus(BranchPackage.PackageStatus.VERIFIED);
            pkg.setVerifiedAt(LocalDateTime.now());
            pkg.setErrorMessage(null);

            return storeReceivedSealedPackage(
                    decrypted.path(),
                    decrypted.sizeBytes(),
                    canonical.path(),
                    canonical.sizeBytes(),
                    pkg
            );
        }
    }

    private PackageResponse storeReceivedSealedPackage(
                                                       java.nio.file.Path decryptedPayload,
                                                       long decryptedPayloadSize,
                                                       java.nio.file.Path canonicalPackage,
                                                       long canonicalPackageSize,
                                                       BranchPackage pkg) throws Exception {
        String bucket = minioConfig.getBucket();
        String decryptedKey = decryptedPackageObjectKey(pkg.getPackageUuid());
        String canonicalKey = importReadyPackageObjectKey(pkg.getPackageUuid());
        boolean decryptedStored = false;
        boolean canonicalStored = false;

        try {
            try (InputStream input = java.nio.file.Files.newInputStream(decryptedPayload)) {
                minioService.uploadRaw(
                        input,
                        bucket,
                        decryptedKey,
                        "application/zip",
                        decryptedPayloadSize
                );
            }
            decryptedStored = true;
            registerRollbackObjectCleanup(bucket, decryptedKey);

            try (InputStream input = java.nio.file.Files.newInputStream(canonicalPackage)) {
                minioService.uploadRaw(
                        input,
                        bucket,
                        canonicalKey,
                        "application/octet-stream",
                        canonicalPackageSize
                );
            }
            canonicalStored = true;
            registerRollbackObjectCleanup(bucket, canonicalKey);

            BranchPackage saved = branchPackageRepository.save(pkg);
            log.info(
                    "Encrypted package received, decrypted and verified; encrypted source not retained: id={}, packageUuid={}, format={}, projects={}, files={}, decryptedBytes={}, canonicalBytes={}",
                    saved.getId(),
                    saved.getPackageUuid(),
                    saved.getPackageFormat(),
                    saved.getProjectsCount(),
                    saved.getFilesCount(),
                    decryptedPayloadSize,
                    canonicalPackageSize
            );
            return PackageResponse.fromEntity(saved);
        } catch (Exception e) {
            if (canonicalStored) {
                try {
                    minioService.deleteFile(bucket, canonicalKey);
                } catch (Exception cleanupError) {
                    log.error("Failed to remove canonical package object: {}", canonicalKey, cleanupError);
                }
            }
            if (decryptedStored) {
                try {
                    minioService.deleteFile(bucket, decryptedKey);
                } catch (Exception cleanupError) {
                    log.error("Failed to remove decrypted package object: {}", decryptedKey, cleanupError);
                }
            }
            throw e;
        }
    }

    private BranchPackage basePackage(UUID packageUuid,
                                      UUID sourceOrganizationId,
                                      UUID sourceTerminalUuid,
                                      String sourceUsername,
                                      String packageName,
                                      String packageHash,
                                      String schemaVersion,
                                      int projectsCount,
                                      int filesCount,
                                      UUID receivedBy) {
        BranchPackage pkg = new BranchPackage();
        pkg.setPackageUuid(packageUuid);
        pkg.setSourceOrganizationId(sourceOrganizationId);
        pkg.setSourceTerminalUuid(sourceTerminalUuid);
        pkg.setSourceUsername(sourceUsername);
        pkg.setPackageName(packageName);
        pkg.setPackageHash(packageHash);
        pkg.setSchemaVersion(schemaVersion);
        pkg.setProjectsCount(projectsCount);
        pkg.setFilesCount(filesCount);
        pkg.setReceivedBy(receivedBy);
        pkg.setReceivedAt(LocalDateTime.now());
        return pkg;
    }

    private void rejectDuplicatePackage(UUID packageUuid, String packageHash) {
        if (branchPackageRepository.existsByPackageUuid(packageUuid)) {
            throw new ConflictException("BranchPackage", "package_uuid", packageUuid);
        }
        if (branchPackageRepository.existsByPackageHash(packageHash)) {
            throw new ConflictException("BranchPackage", "package_hash", packageHash);
        }
    }

    private PackageResponse storeReceivedPackage(MultipartFile packageFile,
                                                 BranchPackage pkg) throws Exception {
        String bucket = minioConfig.getBucket();
        String objectKey = packageObjectKey(pkg.getPackageUuid());
        boolean packageStored = false;
        try {
            minioService.uploadFile(packageFile, bucket, objectKey);
            packageStored = true;
            registerRollbackObjectCleanup(bucket, objectKey);

            BranchPackage saved = branchPackageRepository.save(pkg);
            log.info(
                    "Package received: id={}, packageUuid={}, format={}, encrypted={}, projects={}, files={}, objectKey={}",
                    saved.getId(),
                    saved.getPackageUuid(),
                    saved.getPackageFormat(),
                    saved.getEncrypted(),
                    saved.getProjectsCount(),
                    saved.getFilesCount(),
                    objectKey
            );
            return PackageResponse.fromEntity(saved);
        } catch (Exception e) {
            if (packageStored) {
                try {
                    minioService.deleteFile(bucket, objectKey);
                } catch (Exception cleanupError) {
                    log.error("Failed to remove orphan package object: {}", objectKey, cleanupError);
                }
            }
            throw e;
        }
    }

    public PackageVerifyResponse verify(UUID id) {
        BranchPackage pkg = findPackage(id);

        if (Boolean.TRUE.equals(pkg.getEncrypted())) {
            return verifySealed(pkg);
        }

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

    private PackageVerifyResponse verifySealed(BranchPackage pkg) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode verifiedManifest = null;
        long verifiedBytes = 0L;

        try {
            String bucket = minioConfig.getBucket();

            boolean signedHeaderValid = Boolean.TRUE.equals(pkg.getSignatureValid())
                    && pkg.getHeaderData() != null
                    && pkg.getHeaderHash() != null;
            JsonNode storedHeader = readHeaderForDisplay(pkg);
            if (!signedHeaderValid || storedHeader == null) {
                errors.add("Stored signed header metadata is missing or invalid");
            } else {
                try (InputStream headerStream = new java.io.ByteArrayInputStream(
                        pkg.getHeaderData().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    String actualHeaderHash = packageArchiveService.sha256(headerStream);
                    if (!actualHeaderHash.equalsIgnoreCase(pkg.getHeaderHash())) {
                        signedHeaderValid = false;
                        errors.add("Stored signed header hash mismatch");
                    }
                }
            }

            boolean decryptedIntegrityValid = false;
            if (pkg.getDecryptedPayloadHash() != null) {
                try (InputStream input = minioService.downloadFile(
                        bucket,
                        decryptedPackageObjectKey(pkg.getPackageUuid()))) {
                    String actualDecryptedHash = packageArchiveService.sha256(input);
                    decryptedIntegrityValid =
                            actualDecryptedHash.equalsIgnoreCase(pkg.getDecryptedPayloadHash());
                }
                if (!decryptedIntegrityValid) {
                    errors.add("Decrypted payload hash mismatch");
                }
            } else {
                errors.add("Decrypted payload integrity metadata is missing");
            }

            PackageArchiveService.Verification canonicalVerification = null;
            if (errors.isEmpty()) {
                try (InputStream input = minioService.downloadFile(
                        bucket,
                        importReadyPackageObjectKey(pkg.getPackageUuid()))) {
                    canonicalVerification = packageArchiveService.verifyContents(
                            input,
                            pkg.getManifestHash(),
                            pkg.getSchemaVersion()
                    );
                }

                verifiedManifest = canonicalVerification.manifest();
                verifiedBytes = canonicalVerification.verifiedFileBytes();
                errors.addAll(canonicalVerification.errors());

                if (!canonicalVerification.manifestHashValid()) {
                    errors.add("Canonical manifest hash mismatch");
                }
                if (!canonicalVerification.schemaVersionValid()) {
                    errors.add("Canonical schema_version is invalid");
                }
                if (!canonicalVerification.filesValid()) {
                    errors.add("Canonical package file verification failed");
                }

                int signedProjects = storedHeader != null && storedHeader.path("projects").isArray()
                        ? storedHeader.path("projects").size()
                        : pkg.getProjectsCount();
                int signedFiles = storedHeader != null
                        ? countFiles(storedHeader.path("projects"))
                        : pkg.getFilesCount();

                if (canonicalVerification.projectsCount() != pkg.getProjectsCount()
                        || canonicalVerification.projectsCount() != signedProjects) {
                    errors.add("Canonical project count differs from signed metadata");
                }
                if (canonicalVerification.filesCount() != pkg.getFilesCount()
                        || canonicalVerification.filesCount() != signedFiles) {
                    errors.add("Canonical file count differs from signed metadata");
                }
                warnings.addAll(canonicalVerification.warnings());
            }

            boolean schemaVersionValid =
                    packageArchiveService.isSchemaSupported(pkg.getSchemaVersion());
            boolean valid = signedHeaderValid
                    && decryptedIntegrityValid
                    && schemaVersionValid
                    && canonicalVerification != null
                    && canonicalVerification.manifestHashValid()
                    && canonicalVerification.filesValid()
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
                    canonicalVerification != null && canonicalVerification.manifestHashValid(),
                    signedHeaderValid && decryptedIntegrityValid,
                    schemaVersionValid,
                    canonicalVerification != null
                            ? canonicalVerification.projectsCount()
                            : pkg.getProjectsCount(),
                    canonicalVerification != null
                            ? canonicalVerification.filesCount()
                            : pkg.getFilesCount(),
                    LocalDateTime.now(),
                    errors,
                    warnings,
                    buildProjectDetails(
                            verifiedManifest != null ? verifiedManifest : storedHeader),
                    verifiedBytes
            );
        } catch (Exception e) {
            log.error("Encrypted package verification failed: id={}", pkg.getId(), e);
            pkg.setStatus(BranchPackage.PackageStatus.FAILED);
            pkg.setErrorMessage(e.getMessage());
            branchPackageRepository.save(pkg);
            errors.add("Verification error: " + e.getMessage());
            return new PackageVerifyResponse(
                    pkg.getId(),
                    pkg.getPackageUuid().toString(),
                    false,
                    false,
                    false,
                    false,
                    pkg.getProjectsCount(),
                    pkg.getFilesCount(),
                    LocalDateTime.now(),
                    errors,
                    warnings,
                    buildProjectDetails(readHeaderForDisplay(pkg)),
                    verifiedBytes
            );
        }
    }

    private JsonNode readHeaderForDisplay(BranchPackage pkg) {
        try {
            return pkg.getHeaderData() == null ? null : objectMapper.readTree(pkg.getHeaderData());
        } catch (Exception ignored) {
            return null;
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

            for (JsonNode projectNode : projects) {
                UUID targetOrgId = resolveOrganization(projectNode, pkg, request);
                Organization targetOrganization = organizationRepository.findById(targetOrgId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Target organization does not exist: " + targetOrgId));
                if (!Boolean.TRUE.equals(targetOrganization.getIsActive())) {
                    throw new IllegalArgumentException(
                            "Target organization is inactive: " + targetOrgId);
                }

                resolveProjectType(projectNode, request);
                UUID projectLevelId = resolveProjectConfidentiality(projectNode, request);

                JsonNode files = projectNode.path("files");
                if (files.isArray()) {
                    for (JsonNode fileNode : files) {
                        UUID categoryId = resolveFileCategory(fileNode, request);
                        resolveFileType(fileNode, categoryId, request);
                        resolveFileConfidentiality(fileNode, projectLevelId, request);
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Stored package manifest is invalid", e);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, List<UUID>> getReferenceRequirements(UUID id) {
        BranchPackage pkg = findPackage(id);
        LinkedHashSet<UUID> organizations = new LinkedHashSet<>();
        LinkedHashSet<UUID> projectTypes = new LinkedHashSet<>();
        LinkedHashSet<UUID> confidentialityLevels = new LinkedHashSet<>();
        LinkedHashSet<UUID> fileCategories = new LinkedHashSet<>();
        LinkedHashSet<UUID> fileTypes = new LinkedHashSet<>();

        try {
            JsonNode manifest = objectMapper.readTree(pkg.getManifestData());
            JsonNode projects = manifest.path("projects");
            if (!projects.isArray()) {
                return Map.of(
                        "organizations", List.of(),
                        "projectTypes", List.of(),
                        "confidentialityLevels", List.of(),
                        "fileCategories", List.of(),
                        "fileTypes", List.of()
                );
            }

            for (JsonNode project : projects) {
                UUID organizationId = sourceOrganizationId(project, pkg);
                if (organizationId != null) organizations.add(organizationId);

                UUID projectTypeId = optionalUuid(project, "project_type_id");
                if (projectTypeId != null) projectTypes.add(projectTypeId);

                UUID projectLevelId = optionalUuid(project, "confidentiality_level_id");
                if (projectLevelId != null) confidentialityLevels.add(projectLevelId);

                JsonNode files = project.path("files");
                if (!files.isArray()) continue;
                for (JsonNode file : files) {
                    UUID categoryId = optionalUuid(file, "category_id");
                    if (categoryId != null) fileCategories.add(categoryId);

                    UUID typeId = optionalUuid(file, "type_id");
                    if (typeId != null) fileTypes.add(typeId);

                    UUID fileLevelId = optionalUuid(file, "confidentiality_level_id");
                    if (fileLevelId != null) confidentialityLevels.add(fileLevelId);
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Stored package manifest is invalid", e);
        }

        Map<String, List<UUID>> result = new LinkedHashMap<>();
        result.put("organizations", List.copyOf(organizations));
        result.put("projectTypes", List.copyOf(projectTypes));
        result.put("confidentialityLevels", List.copyOf(confidentialityLevels));
        result.put("fileCategories", List.copyOf(fileCategories));
        result.put("fileTypes", List.copyOf(fileTypes));
        return result;
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
            importOperationService.initializeProjectStatistics(operationId, projects.size());
            if ("2.0".equals(pkg.getSchemaVersion())) {
                importVersionedFamilies(id, operation, pkg, request, importedBy, projects);
                return;
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

                    ImportOperationService.ProjectOutcome outcome;
                    if (result.duplicate()) {
                        outcome = ImportOperationService.ProjectOutcome.DUPLICATE;
                    } else if (result.skipped()) {
                        outcome = ImportOperationService.ProjectOutcome.SKIPPED;
                    } else if (result.partial()) {
                        outcome = ImportOperationService.ProjectOutcome.PARTIAL;
                    } else {
                        outcome = ImportOperationService.ProjectOutcome.IMPORTED;
                    }
                    importOperationService.recordProjectOutcome(operationId, outcome);
                } catch (Exception e) {
                    failedProjects++;
                    importOperationService.recordProjectOutcome(
                            operationId,
                            ImportOperationService.ProjectOutcome.FAILED
                    );
                    cleanupImportedObjects(projectObjects);
                    log.error("Failed to import project from package {}", id, e);
                } finally {
                    importOperationService.heartbeat(operationId);
                }
            }

            if (importedProjects > 0 || (failedProjects == 0 && partialProjects == 0)) {
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


    /**
     * In versioned PKG v2 a project family must import atomically: no isolated
     * "version 2" project is acceptable if version 1 or its files failed.
     * We retain the historical v1 project-by-project import behavior.
     */
    private void importVersionedFamilies(UUID packageId,
                                         ImportOperation operation,
                                         BranchPackage pkg,
                                         PackageImportRequest request,
                                         UUID importedBy,
                                         JsonNode projects) {
        Map<UUID, List<JsonNode>> families = new LinkedHashMap<>();
        for (JsonNode project : projects) {
            families.computeIfAbsent(requiredUuid(project, "project_uid"),
                    ignored -> new ArrayList<>()).add(project);
        }

        int importedVersions = 0;
        int failedVersions = 0;
        int skippedVersions = 0;
        for (List<JsonNode> versions : families.values()) {
            versions.sort(java.util.Comparator.comparingInt(node -> node.path("version_no").asInt()));
            List<StoredObject> objects = new ArrayList<>();
            try {
                boolean skipped = Boolean.TRUE.equals(projectImportTransaction.execute(status -> {
                    try {
                        return importVersionedFamily(versions, pkg, request, importedBy, objects);
                    } catch (Exception exception) {
                        throw new ProjectImportException(exception);
                    }
                }));
                for (int i = 0; i < versions.size(); i++) {
                    importOperationService.recordProjectOutcome(operation.getId(),
                            skipped ? ImportOperationService.ProjectOutcome.SKIPPED
                                    : ImportOperationService.ProjectOutcome.IMPORTED);
                }
                if (skipped) skippedVersions += versions.size();
                else importedVersions += versions.size();
            } catch (Exception exception) {
                cleanupImportedObjects(objects);
                failedVersions += versions.size();
                for (int i = 0; i < versions.size(); i++) {
                    importOperationService.recordProjectOutcome(operation.getId(),
                            ImportOperationService.ProjectOutcome.FAILED);
                }
                log.error("Failed to import versioned project family in package {}", packageId, exception);
            } finally {
                importOperationService.heartbeat(operation.getId());
            }
        }
        if (failedVersions == 0) {
            pkg.setStatus(BranchPackage.PackageStatus.IMPORTED);
            pkg.setImportedAt(LocalDateTime.now());
            pkg.setErrorMessage(null);
            importOperationService.updateStatus(operation.getId(),
                    ImportOperation.Status.SUCCESS, null);
        } else if (importedVersions > 0 || skippedVersions > 0) {
            pkg.setStatus(BranchPackage.PackageStatus.IMPORTED);
            pkg.setImportedAt(LocalDateTime.now());
            pkg.setErrorMessage(failedVersions + " version(s) failed in atomic family imports");
            importOperationService.updateStatus(operation.getId(),
                    ImportOperation.Status.PARTIAL, pkg.getErrorMessage());
        } else {
            pkg.setStatus(BranchPackage.PackageStatus.FAILED);
            pkg.setErrorMessage("All versioned project families failed to import");
            importOperationService.updateStatus(operation.getId(),
                    ImportOperation.Status.FAILED, pkg.getErrorMessage());
        }
        branchPackageRepository.save(pkg);
    }

    /** Returns true when the entire source family was already imported by this same package. */
    private boolean importVersionedFamily(List<JsonNode> versions,
                                           BranchPackage pkg,
                                           PackageImportRequest request,
                                           UUID importedBy,
                                           List<StoredObject> objects) throws Exception {
        int alreadyLinked = 0;
        for (JsonNode node : versions) {
            if (packageProjectRepository.findByPackageIdAndLocalProjectUuid(
                    pkg.getId(), requiredUuid(node, "project_id")).isPresent()) alreadyLinked++;
        }
        if (alreadyLinked == versions.size()) return true;
        if (alreadyLinked > 0) {
            throw new IllegalStateException("Version family was only partly linked to this package");
        }

        // Never attach a version chain to a previously existing unrelated project.
        for (JsonNode node : versions) {
            UUID orgId = resolveOrganization(node, pkg, request);
            if (projectRepository.existsByOrganizationIdAndProjectNumberAndYearAndVersionNo(
                    orgId, requiredText(node, "project_number"), node.path("year").asInt(),
                    node.path("version_no").asInt())) {
                throw new IllegalStateException("Project version already exists in the destination");
            }
        }

        UUID newFamilyUid = UUID.randomUUID();
        Project previous = null;
        for (JsonNode node : versions) {
            UUID orgId = resolveOrganization(node, pkg, request);
            Project project = buildProject(node, orgId, pkg, request, importedBy);
            project.setProjectUid(newFamilyUid);
            project.setVersionNo(node.path("version_no").asInt());
            project.setPreviousProjectId(previous == null ? null : previous.getId());
            project.setIsLatestVersion(node.path("is_latest_version").asBoolean());
            Project saved = projectRepository.saveAndFlush(project);
            createProjectMembers(node, saved.getId());

            int fileCount = 0;
            JsonNode fileNodes = node.path("files");
            if (fileNodes.isArray()) {
                for (JsonNode fileNode : fileNodes) {
                    // Any missing blob/mapping aborts this entire family, not merely one version.
                    objects.add(importFile(fileNode, saved.getId(),
                            saved.getConfidentialityLevelId(), pkg, request, importedBy));
                    fileCount++;
                }
            }

            PackageProject link = createPackageProjectLink(pkg, saved,
                    requiredUuid(node, "project_id"), node, PackageProject.ImportStatus.IMPORTED);
            link.setFilesCount(fileCount);
            link.setFilesImported(fileCount);
            link.setFilesFailed(0);
            packageProjectRepository.save(link);
            previous = saved;
        }
        return false;
    }

    private JsonNode revalidateStoredPackageForImport(BranchPackage pkg) throws Exception {
        String bucket = minioConfig.getBucket();
        String verificationKey = Boolean.TRUE.equals(pkg.getEncrypted())
                ? importReadyPackageObjectKey(pkg.getPackageUuid())
                : packageObjectKey(pkg.getPackageUuid());

        if (!Boolean.TRUE.equals(pkg.getEncrypted())) {
            String actualPackageHash;
            try (InputStream packageStream = minioService.downloadFile(
                    bucket,
                    packageObjectKey(pkg.getPackageUuid()))) {
                actualPackageHash = packageArchiveService.sha256(packageStream);
            }

            if (!actualPackageHash.equalsIgnoreCase(pkg.getPackageHash())) {
                throw new IllegalStateException("Package hash changed after verification");
            }
        } else {
            if (!Boolean.TRUE.equals(pkg.getSignatureValid())
                    || pkg.getHeaderData() == null
                    || pkg.getHeaderHash() == null) {
                throw new IllegalStateException(
                        "Stored signed header metadata is missing for encrypted package");
            }
            try (InputStream headerStream = new java.io.ByteArrayInputStream(
                    pkg.getHeaderData().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                String actualHeaderHash = packageArchiveService.sha256(headerStream);
                if (!actualHeaderHash.equalsIgnoreCase(pkg.getHeaderHash())) {
                    throw new IllegalStateException(
                            "Stored signed header changed after verification");
                }
            }
            try (InputStream decrypted = minioService.downloadFile(
                    bucket,
                    decryptedPackageObjectKey(pkg.getPackageUuid()))) {
                String actualDecryptedHash = packageArchiveService.sha256(decrypted);
                if (pkg.getDecryptedPayloadHash() == null
                        || !actualDecryptedHash.equalsIgnoreCase(pkg.getDecryptedPayloadHash())) {
                    throw new IllegalStateException(
                            "Decrypted payload hash changed after verification");
                }
            }
        }

        PackageArchiveService.Verification verification;
        try (InputStream packageStream = minioService.downloadFile(bucket, verificationKey)) {
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
            return new ImportProjectResult(false, partial, false, true, prior.getProjectId());
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
                    PackageProject.ImportStatus.DUPLICATE
            );
            packageProjectRepository.save(link);
            log.info("Skipped duplicate imported project: source={}, existing={}",
                    sourceProjectUuid, existing.get().getId());
            return new ImportProjectResult(false, false, true, false, existing.get().getId());
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
                            request,
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
                false,
                false,
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
        project.setProjectTypeId(resolveProjectType(projectNode, request));
        project.setConfidentialityLevelId(resolveProjectConfidentiality(projectNode, request));
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
                                    PackageImportRequest request,
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
            String sourcePackageKey = Boolean.TRUE.equals(pkg.getEncrypted())
                    ? importReadyPackageObjectKey(pkg.getPackageUuid())
                    : packageObjectKey(pkg.getPackageUuid());
            try (InputStream packageStream = minioService.downloadFile(
                    bucket,
                    sourcePackageKey)) {

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
            UUID categoryId = resolveFileCategory(fileNode, request);
            file.setCategoryId(categoryId);
            file.setTypeId(resolveFileType(fileNode, categoryId, request));
            file.setConfidentialityLevelId(
                    resolveFileConfidentiality(fileNode, projectConfidentialityLevelId, request));
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
        UUID sourceOrgId = sourceOrganizationId(projectNode, pkg);
        if (sourceOrgId == null) {
            throw new IllegalArgumentException(
                    "Project has no source organization that can be mapped");
        }

        UUID targetOrgId = request.organizationMapping().get(sourceOrgId);
        if (targetOrgId == null) {
            targetOrgId = organizationRepository.findById(sourceOrgId)
                    .filter(org -> Boolean.TRUE.equals(org.getIsActive()))
                    .map(Organization::getId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Missing organization mapping for source organization: " + sourceOrgId));
        }
        return targetOrgId;
    }

    private UUID resolveProjectType(JsonNode projectNode, PackageImportRequest request) {
        UUID sourceId = optionalUuid(projectNode, "project_type_id");
        if (sourceId == null) {
            return null;
        }

        UUID targetId = request.projectTypeMapping().getOrDefault(sourceId, sourceId);
        ProjectType projectType = projectTypeRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        request.projectTypeMapping().containsKey(sourceId)
                                ? "Mapped project type does not exist: " + targetId
                                : "Missing project type mapping for source project type: " + sourceId));
        if (!Boolean.TRUE.equals(projectType.getIsActive())) {
            throw new IllegalArgumentException("Project type is inactive: " + targetId);
        }
        return targetId;
    }

    private UUID resolveProjectConfidentiality(JsonNode projectNode,
                                               PackageImportRequest request) {
        UUID sourceId = optionalUuid(projectNode, "confidentiality_level_id");
        return resolveConfidentiality(sourceId, request, request.confidentialityLevelId());
    }

    private UUID resolveFileConfidentiality(JsonNode fileNode,
                                            UUID projectLevelId,
                                            PackageImportRequest request) {
        UUID sourceId = optionalUuid(fileNode, "confidentiality_level_id");
        return resolveConfidentiality(sourceId, request, projectLevelId);
    }

    private UUID resolveConfidentiality(UUID sourceId,
                                        PackageImportRequest request,
                                        UUID fallbackId) {
        UUID targetId;
        if (sourceId == null) {
            targetId = fallbackId;
            if (targetId == null) {
                return null;
            }
            try {
                confidentialityAccessService.requireReadAccess(targetId);
                return targetId;
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "Invalid default confidentiality level: " + targetId,
                        error
                );
            }
        }

        targetId = request.confidentialityMapping().getOrDefault(sourceId, sourceId);
        try {
            confidentialityAccessService.requireReadAccess(targetId);
            return targetId;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "Missing or inaccessible confidentiality mapping for source level: " + sourceId,
                    error
            );
        }
    }

    private UUID resolveFileCategory(JsonNode fileNode, PackageImportRequest request) {
        UUID sourceId = optionalUuid(fileNode, "category_id");
        if (sourceId == null) {
            return null;
        }

        UUID targetId = request.fileCategoryMapping().getOrDefault(sourceId, sourceId);
        FileCategory category = fileCategoryRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        request.fileCategoryMapping().containsKey(sourceId)
                                ? "Mapped file category does not exist: " + targetId
                                : "Missing file category mapping for source category: " + sourceId));
        if (!Boolean.TRUE.equals(category.getIsActive())) {
            throw new IllegalArgumentException("File category is inactive: " + targetId);
        }
        return targetId;
    }

    private UUID resolveFileType(JsonNode fileNode,
                                 UUID resolvedCategoryId,
                                 PackageImportRequest request) {
        UUID sourceId = optionalUuid(fileNode, "type_id");
        if (sourceId == null) {
            return null;
        }

        UUID targetId = request.fileTypeMapping().getOrDefault(sourceId, sourceId);
        FileType fileType = fileTypeRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        request.fileTypeMapping().containsKey(sourceId)
                                ? "Mapped file type does not exist: " + targetId
                                : "Missing file type mapping for source file type: " + sourceId));
        if (!Boolean.TRUE.equals(fileType.getIsActive())) {
            throw new IllegalArgumentException("File type is inactive: " + targetId);
        }
        if (resolvedCategoryId == null) {
            throw new IllegalArgumentException(
                    "File type requires a mapped file category: " + targetId);
        }
        if (!resolvedCategoryId.equals(fileType.getCategoryId())) {
            throw new IllegalArgumentException(
                    "Mapped file type does not belong to mapped category: " + targetId);
        }
        return targetId;
    }

    private UUID sourceOrganizationId(JsonNode projectNode, BranchPackage pkg) {
        for (String field : List.of(
                "organization_department_id",
                "organization_line_id",
                "organization_factory_id",
                "organization_entity_id",
                "organization_id")) {
            UUID value = optionalUuid(projectNode, field);
            if (value != null) {
                return value;
            }
        }
        return pkg.getSourceOrganizationId();
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
        List<ImportOperation> importOperations = importOperationService.getByPackageId(id);
        operations.put("importOperations", importOperations);

        if (!importOperations.isEmpty()) {
            ImportOperation latest = importOperations.get(0);
            Map<String, Integer> projectStats = new LinkedHashMap<>();
            projectStats.put("total", latest.getProjectsTotal());
            projectStats.put("imported", latest.getProjectsImported());
            projectStats.put("partial", latest.getProjectsPartial());
            projectStats.put("failed", latest.getProjectsFailed());
            projectStats.put("duplicate", latest.getProjectsDuplicate());
            projectStats.put("skipped", latest.getProjectsSkipped());
            operations.put("projectStats", projectStats);
        } else {
            operations.put("projectStats", Map.of(
                    "total", 0,
                    "imported", 0,
                    "partial", 0,
                    "failed", 0,
                    "duplicate", 0,
                    "skipped", 0
            ));
        }

        long totalProjects = packageProjectRepository.countByPackageId(id);
        long importedProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.IMPORTED);
        long skippedProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.SKIPPED);
        long failedProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.FAILED);
        long duplicateProjects = packageProjectRepository.countByPackageIdAndImportStatus(
                id, PackageProject.ImportStatus.DUPLICATE);

        Map<String, Long> linkedProjectStats = new LinkedHashMap<>();
        linkedProjectStats.put("total", totalProjects);
        linkedProjectStats.put("imported", importedProjects);
        linkedProjectStats.put("skipped", skippedProjects);
        linkedProjectStats.put("failed", failedProjects);
        linkedProjectStats.put("duplicate", duplicateProjects);

        operations.put("linkedProjectStats", linkedProjectStats);
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

    private String decryptedPackageObjectKey(UUID packageUuid) {
        return "packages/" + packageUuid + "/decrypted-payload.zip";
    }

    private String importReadyPackageObjectKey(UUID packageUuid) {
        return "packages/" + packageUuid + "/import-ready.pkg";
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
            boolean duplicate,
            boolean skipped,
            UUID centralProjectId
    ) {}
}
