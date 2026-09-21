package com.centralArchiveSystem.Archive.project.member;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ProjectMemberServiceTest {

    @Test
    void rejectsMemberChangesForApprovedProjects() {
        ProjectMemberRepository memberRepository = mock(ProjectMemberRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID projectId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        project.setStatus(Project.ProjectStatus.APPROVED);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        ProjectMemberService service = new ProjectMemberService(
                memberRepository,
                projectRepository,
                confidentiality
        );

        assertThrows(IllegalStateException.class,
                () -> service.delete(projectId, UUID.randomUUID()));

        verify(memberRepository, never()).delete(any());
    }

    @Test
    void listMembersChecksProjectConfidentiality() {
        ProjectMemberRepository memberRepository = mock(ProjectMemberRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);

        UUID projectId = UUID.randomUUID();
        UUID confidentialityId = UUID.randomUUID();

        Project project = new Project();
        project.setId(projectId);
        project.setConfidentialityLevelId(confidentialityId);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(confidentiality).requireReadAccess(confidentialityId);

        ProjectMemberService service = new ProjectMemberService(
                memberRepository,
                projectRepository,
                confidentiality
        );

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.listByProjectId(projectId));

        verify(memberRepository, never()).findByProjectId(projectId);
    }
}
