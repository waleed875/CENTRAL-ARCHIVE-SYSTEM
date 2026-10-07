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
            "createdAt", "updatedAt", "name", "projectNumber", "projectCode",
            "year", "status", "versionNo", "submittedAt");
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
        Pageable pageable = PageRequest.of(normalizePage(page), normalizePageSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return projectRepository.searchAccessible(null, organizationId, status, year,
                null, null, null, null, null, null, true,
                confidentialityAccessService.currentMaxLevelOrder(), pageable).map(ProjectResponse::fromEntity);
    }
    public ProjectResponse getById(UUID id) {
        return ProjectResponse.fromEntity(requireAccessibleProject(id));
    }
    public ProjectResponse create(ProjectRequest request, UUID createdBy) {
        validateOrganizationReference(request.organizationId());
        validateProjectTypeReference(request.projectTypeId());
        confidentialityAccessService.requireReadAccess(request.confidentialityLevelId());
        Optional<Project> existing = projectRepository.findActiveByProjectNumberAndOrganizationIdAndYear(
                request.projectNumber(), request.organizationId(), request.year());
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Project number '" + request.projectNumber()
                    + "' already exists for this organization and year");
        }
        Project project = new Project();
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setStatus(Project.ProjectStatus.DRAFT);
        applyRequestToEntity(project, request);
        project.setSourceType(Project.SourceType.MANUAL);
        project.setSourceReference(null);
        project.setDataCompletionStatus(request.dataCompletionStatus() != null
                ? request.dataCompletionStatus() : Project.DataCompletionStatus.INCOMPLETE);
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
        if (project.getStatus() == Project.ProjectStatus.APPROVED
                || project.getStatus() == Project.ProjectStatus.ARCHIVED
                || project.getStatus() == Project.ProjectStatus.REJECTED) {
            throw new BusinessRuleException("لا يمكن تعديل مشروع حالته " + project.getStatus().describe()
                    + (project.getStatus() == Project.ProjectStatus.REJECTED
                        ? ". الرفض نهائي؛ لا يمكن تعديل هذا السجل أو إنشاء إصدار منه. التصحيح يكون عبر طلب تصحيح قبل الرفض"
                        : ". أنشئ إصداراً جديداً بدلاً من تعديله"));
        }
        if (project.getStatus() == Project.ProjectStatus.UNDER_REVIEW) {
            if (hasRestrictedReviewChanges(request)) {
                throw new BusinessRuleException("المشروع قيد المراجعة: لا يُسمح بتغيير سوى الوصف والملاحظات. "
                        + "انتظر نتيجة المراجعة أو اطلب تصحيحاً");
            }
            if (request.description() != null) project.setDescription(request.description());
            if (request.notes() != null) project.setNotes(request.notes());
            return ProjectResponse.fromEntity(projectRepository.save(project));
        }
        String effectiveProjectNumber = request.projectNumber() != null ? request.projectNumber() : project.getProjectNumber();
        Integer effectiveYear = request.year() != null ? request.year() : project.getYear();
        UUID effectiveOrganizationId = request.organizationId() != null ? request.organizationId() : project.getOrganizationId();
        if (request.organizationId() != null) validateOrganizationReference(request.organizationId());
        if (request.projectTypeId() != null) validateProjectTypeReference(request.projectTypeId());
        boolean identityChanged = !project.getProjectNumber().equals(effectiveProjectNumber)
                || !project.getYear().equals(effectiveYear)
                || !project.getOrganizationId().equals(effectiveOrganizationId);
        if (identityChanged) {
            Optional<Project> existing = projectRepository.findActiveByProjectNumberAndOrganizationIdAndYear(
                    effectiveProjectNumber, effectiveOrganizationId, effectiveYear);
            if (existing.isPresent() && !existing.get().getId().equals(id)) {
                throw new IllegalArgumentException("Project number '" + effectiveProjectNumber
                        + "' already exists for this organization and year");
            }
        }
        applyUpdateToEntity(project, request);
        return ProjectResponse.fromEntity(projectRepository.save(project));
    }
    public ProjectResponse newVersion(UUID id, UUID createdBy, ProjectUpdateRequest overrides) {
        Project original = requireAccessibleProject(id);
        if (overrides != null && overrides.confidentialityLevelId() != null) {
            confidentialityAccessService.requireReadAccess(overrides.confidentialityLevelId());
        }
        UUID organizationId = overrides != null && overrides.organizationId() != null
                ? overrides.organizationId() : original.getOrganizationId();
        UUID projectTypeId = overrides != null && overrides.projectTypeId() != null
                ? overrides.projectTypeId() : original.getProjectTypeId();
        validateOrganizationReference(organizationId);
        validateProjectTypeReference(projectTypeId);
        if (original.getStatus() != Project.ProjectStatus.APPROVED
                && original.getStatus() != Project.ProjectStatus.ARCHIVED) {
            throw new BusinessRuleException("لا يمكن إنشاء إصدار جديد إلا من مشروع معتمد أو مؤرشف. "
                    + "حالة المشروع الحالية: " + original.getStatus().describe());
        }
        if (!Boolean.TRUE.equals(original.getIsLatestVersion())) {
            throw new BusinessRuleException("يوجد إصدار أحدث لهذا المشروع. افتح أحدث إصدار قبل إنشاء إصدار جديد");
        }
        original.setIsLatestVersion(false);
        projectRepository.save(original);
        Project next = new Project();
        next.setProjectUid(original.getProjectUid());
        next.setVersionNo(projectRepository.findMaxVersionNoByProjectUid(original.getProjectUid()) + 1);
        next.setPreviousProjectId(original.getId());
        next.setIsLatestVersion(true);
        next.setOrganizationId(original.getOrganizationId());
        next.setProjectTypeId(original.getProjectTypeId());
        next.setConfidentialityLevelId(original.getConfidentialityLevelId());
        next.setProjectNumber(original.getProjectNumber());
        next.setProjectCode(original.getProjectCode());
        next.setName(original.getName());
        next.setYear(original.getYear());
        next.setExecutingEntity(original.getExecutingEntity());
        next.setSupervisorName(original.getSupervisorName());
        next.setDescription(original.getDescription());
        next.setNotes(original.getNotes());
        next.setTableOfContents(original.getTableOfContents());
        next.setKeywords(original.getKeywords());
        next.setStatus(Project.ProjectStatus.DRAFT);
        next.setDataCompletionStatus(Project.DataCompletionStatus.INCOMPLETE);
        next.setSourceType(original.getSourceType());
        next.setSourceReference(original.getSourceReference());
        next.setCreatedBy(createdBy);
        if (overrides != null) applyUpdateToEntity(next, overrides);
        Project saved = projectRepository.save(next);
        projectMemberService.copyMembers(original.getId(), saved.getId());
        return ProjectResponse.fromEntity(saved);
    }
    public List<ProjectResponse> getVersions(UUID id) {
        Project project = requireAccessibleProject(id);
        return projectRepository.findAccessibleVersions(project.getProjectUid(),
                        confidentialityAccessService.currentMaxLevelOrder()).stream()
                .map(ProjectResponse::fromEntity).collect(Collectors.toList());
    }
    public ProjectResponse submitForReview(UUID id, UUID submittedBy) {
        Project project = requireAccessibleProject(id);
        if (project.getStatus() != Project.ProjectStatus.DRAFT
                && project.getStatus() != Project.ProjectStatus.IMPORTED
                && project.getStatus() != Project.ProjectStatus.NEEDS_CORRECTION) {
            throw new BusinessRuleException("لا يمكن إرسال المشروع للمراجعة إلا من حالة مسودة أو مستورد أو يحتاج تصحيحاً. "
                    + "حالة المشروع الحالية: " + project.getStatus().describe());
        }
        if (project.getDataCompletionStatus() != Project.DataCompletionStatus.COMPLETE) {
            throw new BusinessRuleException("لا يمكن إرسال المشروع للمراجعة قبل ضبط «حالة اكتمال البيانات» على «مكتمل». "
                    + "افتح «تعديل مشروع» واضبطها. الحالة الحالية: " + project.getDataCompletionStatus());
        }
        // Preserve correction instructions across resubmission.
        project.setStatus(Project.ProjectStatus.UNDER_REVIEW);
        project.setSubmittedBy(submittedBy);
        project.setSubmittedAt(LocalDateTime.now());
        return ProjectResponse.fromEntity(projectRepository.save(project));
    }
    public ProjectResponse requestCorrection(UUID id, String reviewNotes) {
        if (reviewNotes == null || reviewNotes.isBlank()) throw new IllegalArgumentException("review_notes is required");
        Project project = requireAccessibleProject(id);
        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("لا يمكن طلب تصحيح إلا لمشروع قيد المراجعة. "
                    + "حالة المشروع الحالية: " + project.getStatus().describe());
        }
        project.setStatus(Project.ProjectStatus.NEEDS_CORRECTION);
        project.setReviewNotes(reviewNotes);
        return ProjectResponse.fromEntity(projectRepository.save(project));
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
        return ProjectResponse.fromEntity(projectRepository.save(project));
    }
    public ProjectResponse reject(UUID id, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) throw new IllegalArgumentException("rejection_reason is required");
        Project project = requireAccessibleProject(id);
        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("لا يمكن رفض إلا مشروع قيد المراجعة. "
                    + "حالة المشروع الحالية: " + project.getStatus().describe());
        }
        project.setStatus(Project.ProjectStatus.REJECTED);
        project.setRejectionReason(rejectionReason);
        return ProjectResponse.fromEntity(projectRepository.save(project));
    }
    public ProjectResponse archive(UUID id) {
        Project project = requireAccessibleProject(id);
        if (project.getStatus() != Project.ProjectStatus.APPROVED) throw new BusinessRuleException("لا يمكن أرشفة إلا المشاريع المعتمدة");
        project.setStatus(Project.ProjectStatus.ARCHIVED);
        return ProjectResponse.fromEntity(projectRepository.save(project));
    }
    /** Only an unused standalone draft may be removed. Version and file history survive. */
    @Transactional
    public void deleteDraft(UUID id) {
        Project project = requireAccessibleProject(id);
        if (project.getStatus() != Project.ProjectStatus.DRAFT) {
            throw new BusinessRuleException("لا يمكن حذف مشروع حالته " + project.getStatus().describe()
                    + ". الحذف متاح فقط لمشروع ما زال مسودة لم يُرسل للمراجعة");
        }
        long files = fileRepository.countByProjectId(id);
        if (files > 0) throw new BusinessRuleException("لا يمكن حذف مشروع عليه ملفات (" + files + "). الملفات سجل محفوظ ولا تُحذف مع المشروع");
        if (!projectMemberService.listByProjectId(id).isEmpty()) throw new BusinessRuleException("لا يمكن حذف مشروع له أعضاء. أزل الأعضاء أولاً");
        if (projectRepository.existsByPreviousProjectId(id)) throw new BusinessRuleException("لا يمكن حذف المشروع لأنه أساس لإصدارات لاحقة منه");
        if (project.getPreviousProjectId() != null) throw new BusinessRuleException("لا يمكن حذف مسودة مرتبطة بإصدار سابق حفاظاً على سلسلة الإصدارات");
        if (packageProjectRepository.existsByProjectId(id)) throw new BusinessRuleException("لا يمكن حذف مشروع مرتبط بحزمة واردة من الترحيل");
        projectRepository.delete(project);
    }
    public Page<ProjectResponse> search(ProjectSearchRequest request, int page, int size) {
        String sortField = request.sortBy() != null && ALLOWED_SORT_FIELDS.contains(request.sortBy()) ? request.sortBy() : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(request.sortDirection()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(normalizePage(page), normalizePageSize(size), Sort.by(direction, sortField));
        return projectRepository.searchAccessible(request.searchTerm(), request.organizationId(), request.status(),
                request.year(), request.projectTypeId(), request.confidentialityLevelId(), request.sourceType(),
                request.projectUid(), request.createdBy(), request.approvedBy(), request.isLatestVersion(),
                confidentialityAccessService.currentMaxLevelOrder(), pageable).map(ProjectResponse::fromEntity);
    }
    public ProjectStatsResponse getStats(UUID organizationId) {
        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();
        long total = projectRepository.countAccessibleLatest(organizationId, maxLevelOrder);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : projectRepository.countAccessibleByStatus(organizationId, maxLevelOrder)) {
            byStatus.put(((Project.ProjectStatus) row[0]).name(), ((Number) row[1]).longValue());
        }
        Map<Integer, Long> byYear = new LinkedHashMap<>();
        for (Object[] row : projectRepository.countAccessibleByYear(organizationId, maxLevelOrder)) {
            byYear.put((Integer) row[0], ((Number) row[1]).longValue());
        }
        return new ProjectStatsResponse(total, byStatus, byYear, total, byStatus.getOrDefault(Project.ProjectStatus.ARCHIVED.name(), 0L));
    }
    private int normalizePage(int page) { return Math.max(page, 0); }
    private int normalizePageSize(int size) { return size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE); }
    private void validateOrganizationReference(UUID id) {
        if (id == null) throw new BusinessRuleException("جهة تنفيذ المشروع مطلوبة");
        Organization organization = organizationRepository.findById(id).orElseThrow(() -> new BusinessRuleException("جهة التنفيذ المحددة غير موجودة"));
        if (!Boolean.TRUE.equals(organization.getIsActive())) throw new BusinessRuleException("جهة التنفيذ المحددة معطّلة، لا يمكن ربط المشروع بها");
    }
    private void validateProjectTypeReference(UUID id) {
        if (id == null) return;
        ProjectType type = projectTypeRepository.findById(id).orElseThrow(() -> new BusinessRuleException("نوع المشروع المحدد غير موجود"));
        if (!Boolean.TRUE.equals(type.getIsActive())) throw new BusinessRuleException("نوع المشروع المحدد معطّل، لا يمكن ربط المشروع به");
    }
    private Project requireAccessibleProject(UUID id) {
        Project project = projectRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));
        confidentialityAccessService.requireReadAccess(project.getConfidentialityLevelId());
        return project;
    }
    private boolean hasRestrictedReviewChanges(ProjectUpdateRequest request) {
        return request.organizationId() != null || request.projectTypeId() != null || request.confidentialityLevelId() != null
                || request.projectNumber() != null || request.projectCode() != null || request.name() != null
                || request.year() != null || request.executingEntity() != null || request.supervisorName() != null
                || request.tableOfContents() != null || request.keywords() != null || request.dataCompletionStatus() != null;
    }
    private void applyUpdateToEntity(Project project, ProjectUpdateRequest request) {
        if (request.organizationId() != null) project.setOrganizationId(request.organizationId());
        if (request.projectTypeId() != null) project.setProjectTypeId(request.projectTypeId());
        if (request.confidentialityLevelId() != null) project.setConfidentialityLevelId(request.confidentialityLevelId());
        if (request.projectNumber() != null) project.setProjectNumber(request.projectNumber());
        if (request.projectCode() != null) project.setProjectCode(request.projectCode());
        if (request.name() != null) project.setName(request.name());
        if (request.year() != null) project.setYear(request.year());
        if (request.executingEntity() != null) project.setExecutingEntity(request.executingEntity());
        if (request.supervisorName() != null) project.setSupervisorName(request.supervisorName());
        if (request.description() != null) project.setDescription(request.description());
        if (request.notes() != null) project.setNotes(request.notes());
        if (request.tableOfContents() != null) project.setTableOfContents(request.tableOfContents());
        if (request.keywords() != null) project.setKeywords(request.keywords());
        if (request.dataCompletionStatus() != null) project.setDataCompletionStatus(request.dataCompletionStatus());
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
        if (request.dataCompletionStatus() != null) project.setDataCompletionStatus(request.dataCompletionStatus());
    }
}
