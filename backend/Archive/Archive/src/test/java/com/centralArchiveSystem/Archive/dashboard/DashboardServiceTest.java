package com.centralArchiveSystem.Archive.dashboard;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackageRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    @Test
    void statsUseConfidentialityAwareAggregates() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        UserRepository users = mock(UserRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        FileRepository files = mock(FileRepository.class);
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        when(confidentiality.currentMaxLevelOrder()).thenReturn(2);
        when(organizations.count()).thenReturn(4L);
        when(organizations.findByIsActive(true)).thenReturn(List.of());
        when(users.count()).thenReturn(3L);
        when(users.findAll()).thenReturn(List.of());

        when(projects.countAccessibleLatest(null, 2)).thenReturn(2L);
        when(projects.countAccessibleByStatus(null, 2)).thenReturn(List.<Object[]>of(
                new Object[]{Project.ProjectStatus.UNDER_REVIEW, 1L},
                new Object[]{Project.ProjectStatus.APPROVED, 1L}
        ));
        when(projects.countAccessibleByYear(null, 2)).thenReturn(List.<Object[]>of(
                new Object[]{2026, 2L}
        ));
        when(projects.countAccessibleBySource(null, 2)).thenReturn(List.<Object[]>of(
                new Object[]{Project.SourceType.MANUAL, 2L}
        ));

        when(files.countAccessibleActive(2)).thenReturn(3L);
        when(files.sumAccessibleActiveSize(2)).thenReturn(4096L);
        when(packages.count()).thenReturn(1L);
        when(packages.findByStatus(BranchPackage.PackageStatus.RECEIVED)).thenReturn(List.of());
        when(packages.findByStatus(BranchPackage.PackageStatus.VERIFIED)).thenReturn(List.of());

        DashboardService service = new DashboardService(
                organizations, users, projects, files, packages, confidentiality);

        var stats = service.getStats();

        assertEquals(2L, stats.projects().total());
        assertEquals(1L, stats.pendingReviews());
        assertEquals(3L, stats.files().total());
        assertEquals(4096L, stats.files().totalSizeBytes());
        assertEquals(2L, stats.projects().bySource().get("MANUAL"));

        verify(confidentiality).currentMaxLevelOrder();
        verify(projects).countAccessibleLatest(null, 2);
        verify(projects).countAccessibleByStatus(null, 2);
        verify(projects).countAccessibleByYear(null, 2);
        verify(projects).countAccessibleBySource(null, 2);
        verify(files).countAccessibleActive(2);
        verify(files).sumAccessibleActiveSize(2);
        verify(projects, never()).findByIsLatestVersion(any());
    }

    @Test
    void recentActivityUsesAccessibleProjectAndFileQueries() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        UserRepository users = mock(UserRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        FileRepository files = mock(FileRepository.class);
        BranchPackageRepository packages = mock(BranchPackageRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        when(confidentiality.currentMaxLevelOrder()).thenReturn(3);
        when(projects.searchAccessible(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(true), eq(3), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(files.findAccessibleRecent(eq(3), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(packages.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        DashboardService service = new DashboardService(
                organizations, users, projects, files, packages, confidentiality);

        assertTrue(service.getRecentActivity(20).isEmpty());

        verify(projects).searchAccessible(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(true), eq(3), any(Pageable.class));
        verify(files).findAccessibleRecent(eq(3), any(Pageable.class));
    }
}
