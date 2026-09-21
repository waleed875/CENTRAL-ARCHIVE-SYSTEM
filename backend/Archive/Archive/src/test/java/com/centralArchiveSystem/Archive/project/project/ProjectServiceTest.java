package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberService;
import com.centralArchiveSystem.Archive.project.project.dto.ProjectResponse;
import com.centralArchiveSystem.Archive.project.project.dto.ProjectUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectServiceTest {

    @Test
    void newVersionPreservesProjectUidAndIncrementsVersion() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectMemberService memberService = mock(ProjectMemberService.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        Project original = baseProject();
        UUID originalId = original.getId();
        UUID projectUid = original.getProjectUid();

        when(repository.findById(originalId)).thenReturn(Optional.of(original));
        when(repository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectService service = new ProjectService(repository, memberService, confidentiality);

        ProjectResponse response = service.newVersion(originalId);

        assertEquals(projectUid, response.projectUid());
        assertEquals(2, response.versionNo());
        assertEquals(originalId, response.previousProjectId());
        assertTrue(response.isLatestVersion());
        assertEquals(Project.ProjectStatus.DRAFT, response.status());
        assertFalse(original.getIsLatestVersion());

        verify(repository, times(2)).save(any(Project.class));
    }

    @Test
    void underReviewAllowsOnlyDescriptionAndNotes() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectMemberService memberService = mock(ProjectMemberService.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        Project project = baseProject();
        project.setStatus(Project.ProjectStatus.UNDER_REVIEW);

        when(repository.findById(project.getId())).thenReturn(Optional.of(project));
        when(repository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectService service = new ProjectService(repository, memberService, confidentiality);

        ProjectUpdateRequest allowed = new ProjectUpdateRequest(
                null, null, null, null, null, null, null,
                null, null, "Updated description", "Updated notes",
                null, null, null
        );

        ProjectResponse updated = service.update(project.getId(), allowed);
        assertEquals("Updated description", updated.description());
        assertEquals("Updated notes", updated.notes());

        ProjectUpdateRequest forbidden = new ProjectUpdateRequest(
                null, null, null, "NEW-NUMBER", null, null, null,
                null, null, null, null,
                null, null, null
        );

        assertThrows(IllegalStateException.class,
                () -> service.update(project.getId(), forbidden));
    }

    @Test
    void approvedProjectCannotBePatched() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectMemberService memberService = mock(ProjectMemberService.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        Project project = baseProject();
        project.setStatus(Project.ProjectStatus.APPROVED);

        when(repository.findById(project.getId())).thenReturn(Optional.of(project));

        ProjectService service = new ProjectService(repository, memberService, confidentiality);

        ProjectUpdateRequest request = new ProjectUpdateRequest(
                null, null, null, null, null, null, null,
                null, null, "changed", null,
                null, null, null
        );

        assertThrows(IllegalStateException.class,
                () -> service.update(project.getId(), request));
        verify(repository, never()).save(any(Project.class));
    }

    @Test
    void projectStatsUseConfidentialityAwareAggregates() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectMemberService memberService = mock(ProjectMemberService.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID organizationId = UUID.randomUUID();
        when(confidentiality.currentMaxLevelOrder()).thenReturn(2);
        when(repository.countAccessibleLatest(organizationId, 2)).thenReturn(5L);
        when(repository.countAccessibleByStatus(organizationId, 2)).thenReturn(
                java.util.List.of(
                        new Object[]{Project.ProjectStatus.APPROVED, 3L},
                        new Object[]{Project.ProjectStatus.ARCHIVED, 2L}
                )
        );
        when(repository.countAccessibleByYear(organizationId, 2)).thenReturn(
                java.util.List.of(
                        new Object[]{2026, 4L},
                        new Object[]{2025, 1L}
                )
        );

        ProjectService service = new ProjectService(repository, memberService, confidentiality);
        var stats = service.getStats(organizationId);

        assertEquals(5L, stats.totalProjects());
        assertEquals(5L, stats.latestVersionCount());
        assertEquals(2L, stats.archivedCount());
        assertEquals(3L, stats.projectsByStatus().get("APPROVED"));
        assertEquals(4L, stats.projectsByYear().get(2026));

        verify(repository).countAccessibleLatest(organizationId, 2);
        verify(repository).countAccessibleByStatus(organizationId, 2);
        verify(repository).countAccessibleByYear(organizationId, 2);
    }


    @Test
    void directProjectAccessRejectsProjectAboveUserClearance() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectMemberService memberService = mock(ProjectMemberService.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        Project project = baseProject();
        UUID levelId = UUID.randomUUID();
        project.setConfidentialityLevelId(levelId);

        when(repository.findById(project.getId())).thenReturn(Optional.of(project));
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(confidentiality).requireReadAccess(levelId);

        ProjectService service = new ProjectService(repository, memberService, confidentiality);

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.getById(project.getId())
        );
        verify(confidentiality).requireReadAccess(levelId);
    }

    private Project baseProject() {
        Project project = new Project();
        project.setId(UUID.randomUUID());
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setOrganizationId(UUID.randomUUID());
        project.setProjectNumber("PRJ-001");
        project.setName("Project");
        project.setYear(2026);
        project.setStatus(Project.ProjectStatus.DRAFT);
        project.setDataCompletionStatus(Project.DataCompletionStatus.COMPLETE);
        project.setSourceType(Project.SourceType.MANUAL);
        project.setCreatedBy(UUID.randomUUID());
        return project;
    }
}
