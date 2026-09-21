package com.centralArchiveSystem.Archive.dashboard;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.dashboard.dto.ActivityResponse;
import com.centralArchiveSystem.Archive.dashboard.dto.DashboardStatsResponse;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackageRepository;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final FileRepository fileRepository;
    private final BranchPackageRepository branchPackageRepository;
    private final ConfidentialityAccessService confidentialityAccessService;

    public DashboardService(OrganizationRepository organizationRepository,
                            UserRepository userRepository,
                            ProjectRepository projectRepository,
                            FileRepository fileRepository,
                            BranchPackageRepository branchPackageRepository,
                            ConfidentialityAccessService confidentialityAccessService) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.branchPackageRepository = branchPackageRepository;
        this.confidentialityAccessService = confidentialityAccessService;
    }

    public DashboardStatsResponse getStats() {
        long totalOrganizations = organizationRepository.count();
        long activeOrganizations = organizationRepository.findByIsActive(true).size();

        long totalUsers = userRepository.count();
        long activeUsers = userRepository.findAll().stream()
                .filter(u -> u.getStatus() == User.UserStatus.ACTIVE)
                .count();

        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();

        long totalProjects = projectRepository.countAccessibleLatest(null, maxLevelOrder);
        Map<String, Long> projectsByStatus = toStatusCounts(
                projectRepository.countAccessibleByStatus(null, maxLevelOrder));
        Map<Integer, Long> projectsByYear = toYearCounts(
                projectRepository.countAccessibleByYear(null, maxLevelOrder));
        Map<String, Long> projectsBySource = toSourceCounts(
                projectRepository.countAccessibleBySource(null, maxLevelOrder));

        long pendingReviewCount = projectsByStatus.getOrDefault(
                Project.ProjectStatus.UNDER_REVIEW.name(), 0L);

        long totalFiles = fileRepository.countAccessibleActive(maxLevelOrder);
        Long totalSizeBytes = fileRepository.sumAccessibleActiveSize(maxLevelOrder);

        long receivedPackages = branchPackageRepository.count();
        long pendingImportPackages = branchPackageRepository.findByStatus(BranchPackage.PackageStatus.RECEIVED).size()
                + branchPackageRepository.findByStatus(BranchPackage.PackageStatus.VERIFIED).size();

        return new DashboardStatsResponse(
                new DashboardStatsResponse.OrganizationStats(totalOrganizations, activeOrganizations),
                new DashboardStatsResponse.UserStats(totalUsers, activeUsers),
                new DashboardStatsResponse.ProjectStats(totalProjects, projectsByStatus, projectsByYear, projectsBySource),
                new DashboardStatsResponse.FileStats(totalFiles, totalSizeBytes),
                new DashboardStatsResponse.PackageStats(receivedPackages, pendingImportPackages),
                pendingReviewCount
        );
    }

    public List<ActivityResponse> getRecentActivity(int limit) {
        List<ActivityResponse> activities = new ArrayList<>();

        int safeLimit = Math.max(1, Math.min(limit, 50));
        PageRequest pageRequest = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Integer maxLevelOrder = confidentialityAccessService.currentMaxLevelOrder();

        List<Project> recentProjects = projectRepository.searchAccessible(
                null, null, null, null, null, null, null, null, null, null,
                true, maxLevelOrder, pageRequest
        ).getContent();
        for (Project project : recentProjects) {
            activities.add(new ActivityResponse(
                    getProjectAction(project.getStatus()),
                    resolveUserById(project.getCreatedBy()),
                    buildProjectDescription(project),
                    "PROJECT",
                    project.getId(),
                    project.getCreatedAt()
            ));
        }

        List<FileEntity> recentFiles = fileRepository
                .findAccessibleRecent(maxLevelOrder, pageRequest)
                .getContent();
        for (FileEntity file : recentFiles) {
            activities.add(new ActivityResponse(
                    "FILE_UPLOADED",
                    resolveUserById(file.getUploadedBy()),
                    "File '" + file.getOriginalName() + "' uploaded",
                    "FILE",
                    file.getId(),
                    file.getCreatedAt()
            ));
        }

        List<BranchPackage> recentPackages = branchPackageRepository
                .findAll(PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        for (BranchPackage pkg : recentPackages) {
            activities.add(new ActivityResponse(
                    "PACKAGE_" + pkg.getStatus().name(),
                    new ActivityResponse.UserSummary(null, pkg.getSourceUsername(), pkg.getSourceUsername()),
                    "Package '" + pkg.getPackageName() + "' " + pkg.getStatus().name().toLowerCase(),
                    "PACKAGE",
                    pkg.getId(),
                    pkg.getCreatedAt()
            ));
        }

        activities.sort(Comparator.comparing(ActivityResponse::createdAt).reversed());

        return activities.stream()
                .limit(safeLimit)
                .collect(Collectors.toList());
    }

    private Map<String, Long> toStatusCounts(List<Object[]> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Project.ProjectStatus status = (Project.ProjectStatus) row[0];
            counts.put(status.name(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private Map<Integer, Long> toYearCounts(List<Object[]> rows) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put((Integer) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private Map<String, Long> toSourceCounts(List<Object[]> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Project.SourceType sourceType = (Project.SourceType) row[0];
            counts.put(
                    sourceType != null ? sourceType.name() : "UNKNOWN",
                    ((Number) row[1]).longValue()
            );
        }
        return counts;
    }

    private String getProjectAction(Project.ProjectStatus status) {
        return switch (status) {
            case DRAFT -> "PROJECT_CREATED";
            case IMPORTED -> "PROJECT_IMPORTED";
            case UNDER_REVIEW -> "PROJECT_SUBMITTED_FOR_REVIEW";
            case NEEDS_CORRECTION -> "PROJECT_CORRECTION_REQUESTED";
            case REJECTED -> "PROJECT_REJECTED";
            case APPROVED -> "PROJECT_APPROVED";
            case ARCHIVED -> "PROJECT_ARCHIVED";
        };
    }

    private String buildProjectDescription(Project project) {
        return "Project '" + project.getName() + "' (" + project.getProjectNumber() + ") - " + project.getStatus().name();
    }

    private ActivityResponse.UserSummary resolveUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsername(username)
                .map(u -> new ActivityResponse.UserSummary(
                        u.getId(),
                        u.getUsername(),
                        u.getFullName()
                ))
                .orElse(new ActivityResponse.UserSummary(
                        null,
                        username,
                        username
                ));
    }

    private ActivityResponse.UserSummary resolveUserById(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(u -> new ActivityResponse.UserSummary(
                        u.getId(),
                        u.getUsername(),
                        u.getFullName()
                ))
                .orElse(null);
    }
}
