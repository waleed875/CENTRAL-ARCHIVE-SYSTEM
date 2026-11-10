package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.package_.PackageProjectRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberService;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectTypeRepository;
import com.centralArchiveSystem.Archive.project.project.dto.ProjectUpdateRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectVersionGuardTest {
    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final ProjectMemberService members = mock(ProjectMemberService.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final ProjectService service = new ProjectService(repository, members,
            mock(ConfidentialityAccessService.class), organizations, mock(ProjectTypeRepository.class),
            mock(FileRepository.class), mock(PackageProjectRepository.class));

    private Project project(Project.ProjectStatus status) {
        Project value = new Project();
        value.setId(UUID.randomUUID());
        value.setProjectUid(UUID.randomUUID());
        value.setVersionNo(1);
        value.setIsLatestVersion(true);
        value.setStatus(status);
        value.setOrganizationId(UUID.randomUUID());
        Organization organization = new Organization();
        organization.setIsActive(true);
        when(organizations.findById(value.getOrganizationId())).thenReturn(Optional.of(organization));
        when(repository.findById(value.getId())).thenReturn(Optional.of(value));
        when(members.listByProjectId(value.getId())).thenReturn(List.of());
        return value;
    }
    @Test
    void obsoleteApprovedVersionCannotCreateAnotherLatestVersion() {
        Project original = project(Project.ProjectStatus.APPROVED);
        original.setIsLatestVersion(false);
        assertThrows(BusinessRuleException.class, () -> service.newVersion(original.getId(), UUID.randomUUID(), null));
        verify(repository, never()).save(any());
        verify(members, never()).copyMembers(any(), any());
    }
    @Test
    void deletingLatestDraftSuccessorRestoresPreviousAsLatest() {
        Project draft = project(Project.ProjectStatus.DRAFT);
        draft.setVersionNo(2);
        UUID previousId = UUID.randomUUID();
        draft.setPreviousProjectId(previousId);

        Project previous = new Project();
        previous.setId(previousId);
        previous.setProjectUid(draft.getProjectUid());
        previous.setVersionNo(1);
        previous.setIsLatestVersion(false);
        previous.setStatus(Project.ProjectStatus.APPROVED);
        when(repository.findById(previousId)).thenReturn(Optional.of(previous));

        service.deleteDraft(draft.getId());

        assertTrue(previous.getIsLatestVersion());
        verify(members).deleteAllByProjectId(draft.getId());
        verify(repository).delete(draft);
        verify(repository).save(previous);
    }
    @Test
    void unusedStandaloneDraftStillCanBeDeleted() {
        Project draft = project(Project.ProjectStatus.DRAFT);
        service.deleteDraft(draft.getId());
        verify(repository).delete(draft);
    }

    @Test
    void rejectedLatestVersionCanBeReopenedWithoutErasingRejectionReason() {
        Project rejected = project(Project.ProjectStatus.REJECTED);
        rejected.setRejectionReason("بيانات المشروع غير مكتملة");
        when(repository.save(rejected)).thenReturn(rejected);

        service.reopenRejected(rejected.getId());

        assertEquals(Project.ProjectStatus.NEEDS_CORRECTION, rejected.getStatus());
        assertEquals("بيانات المشروع غير مكتملة", rejected.getRejectionReason());
        verify(repository).save(rejected);
    }

    @Test
    void obsoleteRejectedVersionCannotBeReopened() {
        Project rejected = project(Project.ProjectStatus.REJECTED);
        rejected.setIsLatestVersion(false);

        assertThrows(BusinessRuleException.class, () -> service.reopenRejected(rejected.getId()));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectedLatestVersionCanStartANewDraftVersion() {
        Project rejected = project(Project.ProjectStatus.REJECTED);
        rejected.setRejectionReason("needs a separate revision");
        when(repository.findMaxVersionNoByProjectUid(rejected.getProjectUid())).thenReturn(1);
        when(repository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        org.mockito.ArgumentCaptor<Project> saved = org.mockito.ArgumentCaptor.forClass(Project.class);
        service.newVersion(rejected.getId(), UUID.randomUUID(), null);

        verify(repository, times(2)).save(saved.capture());
        Project next = saved.getAllValues().get(1);
        assertEquals(Project.ProjectStatus.REJECTED, rejected.getStatus());
        assertFalse(rejected.getIsLatestVersion());
        assertEquals(Project.ProjectStatus.DRAFT, next.getStatus());
        assertEquals(rejected.getProjectUid(), next.getProjectUid());
        assertEquals(2, next.getVersionNo());
        assertEquals(rejected.getId(), next.getPreviousProjectId());
        assertTrue(next.getIsLatestVersion());
        verify(members).copyMembers(rejected.getId(), next.getId());
    }
    @Test
    void submittedTimestampIsAnAdvertisedSortField() {
        assertTrue(ProjectService.allowedSortFields().contains("submittedAt"));
    }
    @Test
    void partialUpdatePermitsNullButRejectsBlankRequiredIdentity() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            ProjectUpdateRequest absent = new ProjectUpdateRequest(null,null,null,null,null,null,null,
                    null,null,null,null,null,null,null);
            assertTrue(validator.validate(absent).isEmpty());
            ProjectUpdateRequest blank = new ProjectUpdateRequest(null,null,null,"",null,"  ",null,
                    null,null,null,null,null,null,null);
            assertEquals(2, validator.validate(blank).size());
        }
    }
}
