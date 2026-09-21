package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberService;
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

    private final ProjectRepository projectRepository;
    private final ProjectMemberService projectMemberService;
    private final ConfidentialityAccessService confidentialityAccessService;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectMemberService projectMemberService,
                          ConfidentialityAccessService confidentialityAccessService) {
        this.projectRepository = projectRepository;
        this.projectMemberService = projectMemberService;
        this.confidentialityAccessService = confidentialityAccessService;
    }

    public Page<ProjectResponse> list(UUID organizationId, Integer year,
                                       Project.ProjectStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
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

        if (request.sourceType() != null) {
            project.setSourceType(request.sourceType());
        } else {
            project.setSourceType(Project.SourceType.MANUAL);
        }

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
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));

        if (project.getStatus() == Project.ProjectStatus.APPROVED ||
            project.getStatus() == Project.ProjectStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Cannot update project in " + project.getStatus() + " status; create a new version instead");
        }

        if (project.getStatus() == Project.ProjectStatus.UNDER_REVIEW) {
            if (hasRestrictedReviewChanges(request)) {
                throw new IllegalStateException(
                        "UNDER_REVIEW projects only allow description and notes changes");
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
        Project original = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));

        if (original.getStatus() != Project.ProjectStatus.APPROVED
                && original.getStatus() != Project.ProjectStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "New versions can only be created from APPROVED or ARCHIVED projects");
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
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));

        if (project.getStatus() != Project.ProjectStatus.DRAFT
                && project.getStatus() != Project.ProjectStatus.IMPORTED
                && project.getStatus() != Project.ProjectStatus.NEEDS_CORRECTION) {
            throw new IllegalStateException(
                    "Can only submit DRAFT, IMPORTED or NEEDS_CORRECTION projects for review");
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

        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));

        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new IllegalStateException("Can only request correction for UNDER_REVIEW projects");
        }

        project.setStatus(Project.ProjectStatus.NEEDS_CORRECTION);
        project.setReviewNotes(reviewNotes);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse approve(UUID id, UUID approvedBy) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));

        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new IllegalStateException("Can only approve UNDER_REVIEW projects");
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

        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));

        if (project.getStatus() != Project.ProjectStatus.UNDER_REVIEW) {
            throw new IllegalStateException("Can only reject UNDER_REVIEW projects");
        }

        project.setStatus(Project.ProjectStatus.REJECTED);
        project.setReviewNotes(rejectionReason);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public ProjectResponse archive(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found with id: " + id));

        if (project.getStatus() != Project.ProjectStatus.APPROVED) {
            throw new IllegalStateException("Can only archive APPROVED projects");
        }

        project.setStatus(Project.ProjectStatus.ARCHIVED);
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    public Page<ProjectResponse> search(ProjectSearchRequest request, int page, int size) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by("desc".equalsIgnoreCase(request.sortDirection())
                        ? Sort.Direction.DESC : Sort.Direction.ASC,
                        request.sortBy()));

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
        }
        if (request.sourceType() != null) {
            project.setSourceType(request.sourceType());
        }
        if (request.sourceReference() != null) {
            project.setSourceReference(request.sourceReference());
        }
    }
}
