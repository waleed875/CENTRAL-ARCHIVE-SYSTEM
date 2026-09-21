package com.centralArchiveSystem.Archive.dashboard;

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

    public DashboardService(OrganizationRepository organizationRepository,
                            UserRepository userRepository,
                            ProjectRepository projectRepository,
                            FileRepository fileRepository,
                            BranchPackageRepository branchPackageRepository) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.branchPackageRepository = branchPackageRepository;
    }

    public DashboardStatsResponse getStats() {
        long totalOrganizations = organizationRepository.count();
        long activeOrganizations = organizationRepository.findByIsActive(true).size();

        long totalUsers = userRepository.count();
        long activeUsers = userRepository.findAll().stream()
                .filter(u -> u.getStatus() == User.UserStatus.ACTIVE)
                .count();

        List<Project> latestProjects = projectRepository.findByIsLatestVersion(true);
        long totalProjects = latestProjects.size();

        Map<String, Long> projectsByStatus = latestProjects.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getStatus().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));

        Map<Integer, Long> projectsByYear = latestProjects.stream()
                .collect(Collectors.groupingBy(
                        Project::getYear,
                        LinkedHashMap::new,
                        Collectors.counting()));

        Map<String, Long> projectsBySource = latestProjects.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getSourceType() != null ? p.getSourceType().name() : "UNKNOWN",
                        LinkedHashMap::new,
                        Collectors.counting()));

        long pendingReviewCount = latestProjects.stream()
                .filter(p -> p.getStatus() == Project.ProjectStatus.UNDER_REVIEW)
                .count();

        List<FileEntity> activeFiles = fileRepository.findAll().stream()
                .filter(f -> f.getDeletedAt() == null)
                .collect(Collectors.toList());
        long totalFiles = activeFiles.size();
        Long totalSizeBytes = activeFiles.stream()
                .mapToLong(FileEntity::getSizeBytes)
                .sum();

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

        PageRequest pageRequest = PageRequest.of(0, Math.min(limit, 50), Sort.by(Sort.Direction.DESC, "createdAt"));

        List<Project> recentProjects = projectRepository.findAll(pageRequest).getContent();
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

        List<FileEntity> recentFiles = fileRepository.findAll(pageRequest).getContent();
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
                .findAll(PageRequest.of(0, Math.min(limit, 50), Sort.by(Sort.Direction.DESC, "createdAt")))
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
                .limit(limit)
                .collect(Collectors.toList());
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
