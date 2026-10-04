package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.package_.PackageProjectRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberService;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectType;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectTypeRepository;
import com.centralArchiveSystem.Archive.project.project.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProjectService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt",
            "updatedAt",
            "name",
            "projectNumber",
            "projectCode",
            "year",
            "status",
            "versionNo"
    );

    /**
     * The sort fields a project search accepts, for {@code GET /api/v1/enums}.
     *
     * <p>Public because the field list was previously invisible to any client, so a search sorted by
     * anything else was quietly reordered by createdAt instead. Exposing it lets the search form
     * offer only fields that work. A field outside this set is still accepted and ignored, not
     * rejected, so publishing the list is documentation rather than validation.
     */
    public static List<String> allowedSortFields() {
        return ALLOWED_SORT_FIELDS.stream().sorted().toList();
    }

    private final ProjectRepository projectRepository;
    private final ProjectMemberService projectMemberService;
    private final ConfidentialityAccessService confidentialityAccessService;
    private final OrganizationRepository organizationRepository;
    private final ProjectTypeRepository projectTypeRepository;
    private final FileRepository fileRepository;
    private final PackageProjectRepository packageProjectRepository;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectMemberService projectMemberService,
                          ConfidentialityAccessService confidentialityAccessService,
                          OrganizationRepository organizationRepository,
                          ProjectTypeRepository projectTypeRepository,
                          FileRepository fileRepository,
                          PackageProjectRepository packageProjectRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberService = projectMemberService;
        this.confidentialityAccessService = confidentialityAccessService;
        this.fileRepository = fileRepository;
        this.packageProjectRepository = packageProjectRepository;
        this.organizationRepository = organizationRepository;
        this.projectTypeRepository = projectTypeRepository;
    }

    public Page<ProjectResponse> list(UUID organizationId, Integer year,
                                       Project.ProjectStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(
                normalizePage(page),
                normalizePageSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();

        return projectRepository.searchAccessible(
                null,
                organizationId,
                status,
                year,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                maxLevelOrder,
                pageable
        ).map(ProjectResponse::fromEntity);
    }

    public ProjectResponse getById(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));
        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());
        return ProjectResponse.fromEntity(project);
    }

    public ProjectResponse create(ProjectRequest request, UUID createdBy) {
        validateOrganizationReference(request.organizationId());
        validateProjectTypeReference(request.projectTypeId());
        confidentialityAccessService.requireReadAccess(request.confidentialityLevelId());

        Optional<Project> existing = projectRepository.findActiveByProjectNumberAndOrganizationIdAndYear(
                request.projectNumber(), request.organizationId(), request.year());

        if (existing.isPresent()) {
            throw new IllegalArgumentException(
                    "Project number '" + request.projectNumber() + "' already exists for this organization and year");
        }

        Project project = new Project();
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setStatus(Project.ProjectStatus.DRAFT);
        applyRequestToEntity(project, request);

        project.setSourceType(Project.SourceType.MANUAL);
        project.setSourceReference(null);

        if (request.dataCompletionStatus() != null) {
            project.setDataCompletionStatus(request.dataCompletionStatus());
        } else {
            project.setDataCompletionStatus(Project.DataCompletionStatus.INCOMPLETE);
        }

        project.setCreatedBy(createdBy);

        Project saved = projectRepository.save(project);

        if (request.members() != null) {
            request.members().forEach(member -> projectMemberService.create(saved.getId(), member));
        }

        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse update(UUID id, ProjectUpdateRequest request) {
        Project project = requireAccessibleProject(id);
        if (request.confidentialityLevelId() != null) {
            confidentialityAccessService.requireReadAccess(request.confidentialityLevelId());
        }

        if (project.getStatus() == Project.ProjectStatus.APPROVED ||
            project.getStatus() == Project.ProjectStatus.ARCHIVED ||
            project.getStatus() == Project.ProjectStatus.REJECTED) {
            throw new BusinessRuleException(
                    "لا يمكن تعديل مشروع حالته " + project.getStatus().describe()
                            + (project.getStatus() == Project.ProjectStatus.REJECTED
                                ? ". الرفض نهائي، فأنشئ إصداراً جديداً بدلاً من ذلك"
                                : ". أنشئ إصداراً جديداً بدلاً من تعديله"));
        }

        if (project.getStatus() == Project.ProjectStatus.UNDER_REVIEW) {
            if (hasRestrictedReviewChanges(request)) {
                throw new BusinessRuleException(
                        "المشروع قيد المراجعة: لا يُسمح بتغيير سوى الوصف والملاحظات. "
                                + "انتظر نتيجة المراجعة أو اطلب تصحيحاً");
            }
            if (request.description() != null) {
                project.setDescription(request.description());
            }
            if (request.notes() != null) {
                project.setNotes(request.notes());
            }
            return ProjectResponse.fromEntity(projectRepository.save(project));
        }

        String effectiveProjectNumber = request.projectNumber() != null
                ? request.projectNumber()
                : project.getProjectNumber();
        Integer effectiveYear = request.year() != null
                ? request.year()
                : project.getYear();
        UUID effectiveOrganizationId = request.organizationId() != null
                ? request.organizationId()
                : project.getOrganizationId();

        if (request.organizationId() != null) {
            validateOrganizationReference(request.organizationId());
        }
        if (request.projectTypeId() != null) {
            validateProjectTypeReference(request.projectTypeId());
        }

        boolean identityChanged =
                !project.getProjectNumber().equals(effectiveProjectNumber)
                || !project.getYear().equals(effectiveYear)
                || !project.getOrganizationId().equals(effectiveOrganizationId);

        if (identityChanged) {
            Optional<Project> existing = projectRepository.findActiveByProjectNumberAndOrganizationIdAndYear(
                    effectiveProjectNumber,
                    effectiveOrganizationId,
                    effectiveYear
            );

            if (existing.isPresent() && !existing.get().getId().equals(id)) {
                throw new IllegalArgumentException(
                        "Project number '" + effectiveProjectNumber
                                + "' already exists for this organization and year");
            }
        }

        applyUpdateToEntity(project, request);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse newVersion(UUID id, UUID createdBy, ProjectUpdateRequest overrides) {
        Project original = requireAccessibleProject(id);
        if (overrides != null && overrides.confidentialityLevelId() != null) {
            confidentialityAccessService.requireReadAccess(overrides.confidentialityLevelId());
        }

        UUID versionOrganizationId = overrides != null && overrides.organizationId() != null
                ? overrides.organizationId()
                : original.getOrganizationId();
        UUID versionProjectTypeId = overrides != null && overrides.projectTypeId() != null
                ? overrides.projectTypeId()
                : original.getProjectTypeId();
        validateOrganizationReference(versionOrganizationId);
        validateProjectTypeReference(versionProjectTypeId);

        if (original.getStatus() != Project.ProjectStatus.APPROVED
                && original.getStatus() != Project.ProjectStatus.ARCHIVED) {
            throw new BusinessRuleException(
                    "لا يمكن إنشاء إصدار جديد إلا من مشروع معتمد أو مؤرشف. "
                            + "حالة المشروع الحالية: " + original.getStatus().describe());
        }

        original.setIsLatestVersion(false);
        projectRepository.save(original);

        Project newVersionProject = new Project();
        newVersionProject.setProjectUid(original.getProjectUid());
        newVersionProject.setVersionNo(
                projectRepository.findMaxVersionNoByProjectUid(original.getProjectUid()) + 1
        );
        newVersionProject.setPreviousProjectId(original.getId());
        newVersionProject.setIsLatestVersion(true);
        newVersionProject.setOrganizationId(original.getOrganizationId());
        newVersionProject.setProjectTypeId(original.getProjectTypeId());
        newVersionProject.setConfidentialityLevelId(original.getConfidentialityLevelId());
        newVersionProject.setProjectNumber(original.getProjectNumber());
        newVersionProject.setProjectCode(original.getProjectCode());
        newVersionProject.setName(original.getName());
        newVersionProject.setYear(original.getYear());
        newVersionProject.setExecutingEntity(original.getExecutingEntity());
        newVersionProject.setSupervisorName(original.getSupervisorName());
        newVersionProject.setDescription(original.getDescription());
        newVersionProject.setNotes(original.getNotes());
        newVersionProject.setTableOfContents(original.getTableOfContents());
        newVersionProject.setKeywords(original.getKeywords());
        newVersionProject.setStatus(Project.ProjectStatus.DRAFT);
        newVersionProject.setDataCompletionStatus(Project.DataCompletionStatus.INCOMPLETE);
        newVersionProject.setSourceType(original.getSourceType());
        newVersionProject.setSourceReference(original.getSourceReference());
        newVersionProject.setCreatedBy(createdBy);

        if (overrides != null) {
            applyUpdateToEntity(newVersionProject, overrides);
        }

        Project saved = projectRepository.save(newVersionProject);
        projectMemberService.copyMembers(original.getId(), saved.getId());
        return ProjectResponse.fromEntity(saved);
    }

    public List<ProjectResponse> getVersions(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));
        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());

        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();
        return projectRepository.findAccessibleVersions(project.getProjectUid(), maxLevelOrder)
                .stream()
                .map(ProjectResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public ProjectResponse submitForReview(UUID id) {
        Project project = requireAccessibleProject(id);

        if (project.getStatus() != Project.ProjectStatus.DRAFT
                && project.getStatus() != Project.ProjectStatus.IMPORTED
                && project.getStatus() != Project.ProjectStatus.NEEDS_CORRECTION) {
            throw new BusinessRuleException(
                    "لا يمكن إرسال المشروع للمراجعة إلا من حالة مسودة أو مستورد أو يحتاج تصحيحاً. "
                            + "حالة المشروع الحالية: " + project.getStatus().describe());
        }
        if (project.getDataCompletionStatus() != Project.DataCompletionStatus.COMPLETE) {
            throw new BusinessRuleException(
                    "لا يمكن إرسال مشروع ناقص البيانات للمراجعة. "
                            + "أكمل الحقول الناقصة أولاً — راجع تقرير البيانات الناقصة");
        }

        project.setStatus(Project.ProjectStatus.UNDER_REVIEW);
        project.setReviewNotes(null);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse requestCorrection(UUID id, String reviewNotes) {
        if (reviewNotes == null || reviewNotes.isBlank()) {
            throw new IllegalArgumentException("review_notes is required");
        }

        Project project = requireAccessibleProject(id);

        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("لا يمكن طلب تصحيح إلا لمشروع قيد المراجعة. "
                    + "حالة المشروع الحالية: " + project.getStatus().describe());
        }

        project.setStatus(Project.ProjectStatus.NEEDS_CORRECTION);
        project.setReviewNotes(reviewNotes);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse approve(UUID id, UUID approvedBy) {
        Project project = requireAccessibleProject(id);

        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("لا يمكن اعتماد إلا مشروع قيد المراجعة. "
                    + "حالة المشروع الحالية: " + project.getStatus().describe());
        }

        project.setStatus(Project.ProjectStatus.APPROVED);
        project.setApprovedBy(approvedBy);
        project.setApprovedAt(LocalDateTime.now());
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse reject(UUID id, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("rejection_reason is required");
        }

        Project project = requireAccessibleProject(id);

        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("لا يمكن رفض إلا مشروع قيد المراجعة. "
                    + "حالة المشروع الحالية: " + project.getStatus().describe());
        }

        project.setStatus(Project.ProjectStatus.REJECTED);
        project.setReviewNotes(rejectionReason);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse archive(UUID id) {
        Project project = requireAccessibleProject(id);

        if (project.getStatus() != Project.ProjectStatus.APPROVED) {
            throw new BusinessRuleException("لا يمكن أرشفة إلا المشاريع المعتمدة");
        }

        project.setStatus(Project.ProjectStatus.ARCHIVED);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    /**
     * Removes a project that was entered by mistake, and nothing else.
     *
     * <p>This is the one removal the system's design otherwise forbids, so the conditions are drawn
     * from what a project accumulates as it passes through the archive. A draft is the state before
     * any of that: it has not been submitted, reviewed, approved or archived, so it holds no
     * approved record, and it cannot have entered any report. The four checks below are each a thing
     * that, once true, makes the row part of the archive rather than a stray keystroke:
     *
     * <ul>
     *   <li><b>Status is DRAFT.</b> Every other status means the project was submitted, and a
     *       submission is itself a record of somebody's work — an auditor asked what was in review on
     *       a given date has to be able to find it. Archive is the correct exit for those, and
     *       {@link #archive} is where it lives.</li>
     *   <li><b>No files, live or deleted.</b> {@code project.files} cascades, so removing the project
     *       would remove the file records too, and the design says a file is never destroyed — it is
     *       marked deleted and kept, with its reason and the person who deleted it. A file that was
     *       uploaded and then soft-deleted still has a row, and that row is still a record.</li>
     *   <li><b>No members.</b> Members cascade as well, and a member row names a person and an
     *       organisation.</li>
     *   <li><b>Not part of a version chain and not referenced by a package.</b> A later version
     *       pointing here would be left dangling, and a package row pointing here means the project
     *       came from an import and belongs to a package's history. The database enforces the second
     *       with a NO ACTION foreign key and would answer with a raw constraint violation; checking
     *       it here means the person is told which of the two it is.</li>
     * </ul>
     *
     * <p>The row itself goes. There is no soft-delete column on the project table, and adding one to
     * the archive's central table is a schema decision beyond what this change should decide on its
     * own. What is not lost is the fact: the audit aspect records the removal, and audit rows are
     * write-once — the schema blocks their deletion outright — so "this project existed and was
     * removed on this date by this person" survives the removal.
     */
    @Transactional
    public void deleteDraft(UUID id) {
        Project project = requireAccessibleProject(id);

        if (project.getStatus() != Project.ProjectStatus.DRAFT) {
            throw new BusinessRuleException(
                    "لا يمكن حذف مشروع حالته " + project.getStatus().describe()
                            + ". الحذف متاح فقط لمشروع ما زال مسودة لم يُرسل للمراجعة. "
                            + "إن كان خطأ في بيانات مشروع زُخِمت بالفعل، فراجع الحالة المتاحة له بدل الحذف");
        }

        long files = fileRepository.countByProjectId(id);
        if (files > 0) {
            throw new BusinessRuleException(
                    "لا يمكن حذف مشروع عليه ملفات (" + files + "). الملفات سجل محفوظ ولا تُحذف مع المشروع");
        }

        if (!projectMemberService.listByProjectId(id).isEmpty()) {
            throw new BusinessRuleException("لا يمكن حذف مشروع له أعضاء. أزل الأعضاء أولاً");
        }

        if (projectRepository.existsByPreviousProjectId(id)) {
            throw new BusinessRuleException("لا يمكن حذف مشروع因为他 أساس لإصدارات لاحقة منه");
        }

        if (packageProjectRepository.existsByProjectId(id)) {
            throw new BusinessRuleException("لا يمكن حذف مشروع مرتبط بحزمة واردة من الترحيل");
        }

        projectRepository.delete(project);
    }

    public Page<ProjectResponse> search(ProjectSearchRequest request, int page, int size) {
        String sortField = ALLOWED_SORT_FIELDS.contains(request.sortBy())
                ? request.sortBy()
                : "createdAt";
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(request.sortDirection())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(
                normalizePage(page),
                normalizePageSize(size),
                Sort.by(sortDirection, sortField)
        );

        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();

        return projectRepository.searchAccessible(
                request.searchTerm(),
                request.organizationId(),
                request.status(),
                request.year(),
                request.projectTypeId(),
                request.confidentialityLevelId(),
                request.sourceType(),
                request.projectUid(),
                request.createdBy(),
                request.approvedBy(),
                request.isLatestVersion(),
                maxLevelOrder,
                pageable
        ).map(ProjectResponse::fromEntity);
    }

    public ProjectStatsResponse getStats(UUID organizationId) {
        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();

        long totalProjects = projectRepository.countAccessibleLatest(
                organizationId,
                maxLevelOrder
        );

        Map<String, Long> projectsByStatus = new LinkedHashMap<>();
        for (Object[] row : projectRepository.countAccessibleByStatus(
                organizationId,
                maxLevelOrder
        )) {
            Project.ProjectStatus status = (Project.ProjectStatus) row[0];
            projectsByStatus.put(status.name(), (Long) row[1]);
        }

        Map<Integer, Long> projectsByYear = new LinkedHashMap<>();
        for (Object[] row : projectRepository.countAccessibleByYear(
                organizationId,
                maxLevelOrder
        )) {
            projectsByYear.put((Integer) row[0], (Long) row[1]);
        }

        long archivedCount = projectsByStatus.getOrDefault(
                Project.ProjectStatus.ARCHIVED.name(),
                0L
        );

        return new ProjectStatsResponse(
                totalProjects,
                projectsByStatus,
                projectsByYear,
                totalProjects,
                archivedCount
        );
    }

    private int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private int normalizePageSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private void validateOrganizationReference(UUID organizationId) {
        if (organizationId == null) {
            throw new BusinessRuleException("جهة تنفيذ المشروع مطلوبة");
        }

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessRuleException(
                        "جهة التنفيذ المحددة غير موجودة"));
        if (!Boolean.TRUE.equals(organization.getIsActive())) {
            throw new BusinessRuleException(
                    "جهة التنفيذ المحددة معطّلة، لا يمكن ربط المشروع بها");
        }
    }

    private void validateProjectTypeReference(UUID projectTypeId) {
        if (projectTypeId == null) {
            return;
        }

        ProjectType projectType = projectTypeRepository.findById(projectTypeId)
                .orElseThrow(() -> new BusinessRuleException(
                        "نوع المشروع المحدد غير موجود"));
        if (!Boolean.TRUE.equals(projectType.getIsActive())) {
            throw new BusinessRuleException(
                    "نوع المشروع المحدد معطّل، لا يمكن ربط المشروع به");
        }
    }

    private Project requireAccessibleProject(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));
        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());
        return project;
    }

    private boolean hasRestrictedReviewChanges(ProjectUpdateRequest request) {
        return request.organizationId() != null
                || request.projectTypeId() != null
                || request.confidentialityLevelId() != null
                || request.projectNumber() != null
                || request.projectCode() != null
                || request.name() != null
                || request.year() != null
                || request.executingEntity() != null
                || request.supervisorName() != null
                || request.tableOfContents() != null
                || request.keywords() != null
                || request.dataCompletionStatus() != null;
    }

    private void applyUpdateToEntity(Project project, ProjectUpdateRequest request) {
        if (request.organizationId() != null) {
            project.setOrganizationId(request.organizationId());
        }
        if (request.projectTypeId() != null) {
            project.setProjectTypeId(request.projectTypeId());
        }
        if (request.confidentialityLevelId() != null) {
            project.setConfidentialityLevelId(request.confidentialityLevelId());
        }
        if (request.projectNumber() != null) {
            project.setProjectNumber(request.projectNumber());
        }
        if (request.projectCode() != null) {
            project.setProjectCode(request.projectCode());
        }
        if (request.name() != null) {
            project.setName(request.name());
        }
        if (request.year() != null) {
            project.setYear(request.year());
        }
        if (request.executingEntity() != null) {
            project.setExecutingEntity(request.executingEntity());
        }
        if (request.supervisorName() != null) {
            project.setSupervisorName(request.supervisorName());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.notes() != null) {
            project.setNotes(request.notes());
        }
        if (request.tableOfContents() != null) {
            project.setTableOfContents(request.tableOfContents());
        }
        if (request.keywords() != null) {
            project.setKeywords(request.keywords());
        }
        if (request.dataCompletionStatus() != null) {
            project.setDataCompletionStatus(request.dataCompletionStatus());
        }
    }

    private void applyRequestToEntity(Project project, ProjectRequest request) {
        project.setOrganizationId(request.organizationId());
        project.setProjectTypeId(request.projectTypeId());
        project.setConfidentialityLevelId(request.confidentialityLevelId());
        project.setProjectNumber(request.projectNumber());
        project.setProjectCode(request.projectCode());
        project.setName(request.name());
        project.setYear(request.year());
        project.setExecutingEntity(request.executingEntity());
        project.setSupervisorName(request.supervisorName());
        project.setDescription(request.description());
        project.setNotes(request.notes());
        project.setTableOfContents(request.tableOfContents());
        project.setKeywords(request.keywords());

        if (request.dataCompletionStatus() != null) {
            project.setDataCompletionStatus(request.dataCompletionStatus());
        }    }
}
