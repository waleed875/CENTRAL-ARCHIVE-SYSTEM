package com.centralArchiveSystem.Archive.core.organization;

import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationRequest;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationUpdateRequest;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrganizationServiceTest {

    @Test
    void factoryRequiresCompanyParent() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID parentId = UUID.randomUUID();
        Organization parent = organization(parentId, Organization.OrganizationType.FACTORY, null, true);
        when(organizations.findById(parentId)).thenReturn(Optional.of(parent));

        OrganizationService service = new OrganizationService(organizations, projects);

        OrganizationRequest request = new OrganizationRequest(
                parentId, "Factory B", "FAC-B", "FACTORY", null
        );

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
        verify(organizations, never()).save(any());
    }

    @Test
    void companyCannotHaveParent() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        OrganizationService service = new OrganizationService(organizations, projects);

        OrganizationRequest request = new OrganizationRequest(
                UUID.randomUUID(), "Company", "COMP", "COMPANY", null
        );

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
        verify(organizations, never()).save(any());
    }

    @Test
    void deactivationRejectsActiveChildren() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID id = UUID.randomUUID();
        Organization org = organization(id, Organization.OrganizationType.COMPANY, null, true);
        Organization child = organization(UUID.randomUUID(), Organization.OrganizationType.FACTORY, id, true);

        when(organizations.findById(id)).thenReturn(Optional.of(org));
        when(organizations.findByParentId(id)).thenReturn(List.of(child));

        OrganizationService service = new OrganizationService(organizations, projects);

        assertThrows(IllegalStateException.class,
                () -> service.update(id, new OrganizationUpdateRequest(null, null, false, null)));
        verify(organizations, never()).save(org);
    }

    @Test
    void deactivationRejectsActiveProjects() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID id = UUID.randomUUID();
        Organization org = organization(id, Organization.OrganizationType.DEPARTMENT, UUID.randomUUID(), true);

        when(organizations.findById(id)).thenReturn(Optional.of(org));
        when(organizations.findByParentId(id)).thenReturn(List.of());
        when(projects.countActiveProjectsByOrganizationId(id)).thenReturn(2L);

        OrganizationService service = new OrganizationService(organizations, projects);

        assertThrows(IllegalStateException.class,
                () -> service.delete(id));
        verify(organizations, never()).save(org);
    }

    @Test
    void updateChangesParentWhenNewParentMatchesHierarchy() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID companyId = UUID.randomUUID();
        UUID factoryId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        Organization company = organization(companyId, Organization.OrganizationType.COMPANY, null, true);
        Organization newFactory = organization(factoryId, Organization.OrganizationType.FACTORY, companyId, true);
        Organization line = organization(lineId, Organization.OrganizationType.LINE, UUID.randomUUID(), true);

        when(organizations.findById(lineId)).thenReturn(Optional.of(line));
        when(organizations.findById(factoryId)).thenReturn(Optional.of(newFactory));
        when(organizations.findById(companyId)).thenReturn(Optional.of(company));
        when(organizations.save(line)).thenReturn(line);
        when(organizations.countByParentId(lineId)).thenReturn(0L);
        when(projects.countByOrganizationId(lineId)).thenReturn(0L);

        OrganizationService service = new OrganizationService(organizations, projects);
        var updated = service.update(lineId, new OrganizationUpdateRequest(null, null, null, factoryId));

        assertEquals(factoryId, line.getParentId());
        assertEquals(factoryId, updated.parentId());
        verify(organizations).save(line);
    }

    @Test
    void updateRejectsParentThatDoesNotMatchOrganizationHierarchy() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID companyId = UUID.randomUUID();
        UUID factoryId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        Organization company = organization(companyId, Organization.OrganizationType.COMPANY, null, true);
        Organization line = organization(lineId, Organization.OrganizationType.LINE, factoryId, true);
        Organization notACompany = organization(UUID.randomUUID(), Organization.OrganizationType.FACTORY, companyId, true);

        when(organizations.findById(factoryId)).thenReturn(Optional.of(organization(factoryId, Organization.OrganizationType.FACTORY, companyId, true)));
        when(organizations.findById(lineId)).thenReturn(Optional.of(line));
        when(organizations.findById(notACompany.getId())).thenReturn(Optional.of(notACompany));

        OrganizationService service = new OrganizationService(organizations, projects);
        assertThrows(IllegalArgumentException.class,
                () -> service.update(lineId, new OrganizationUpdateRequest(null, null, null, notACompany.getId())));
        verify(organizations, never()).save(any());
    }

    @Test
    void responseContainsProjectCount() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID id = UUID.randomUUID();
        Organization org = organization(id, Organization.OrganizationType.COMPANY, null, true);
        when(organizations.findById(id)).thenReturn(Optional.of(org));
        when(organizations.countByParentId(id)).thenReturn(1L);
        when(projects.countByOrganizationId(id)).thenReturn(7L);

        OrganizationService service = new OrganizationService(organizations, projects);
        var response = service.getById(id);

        assertEquals(1L, response.childrenCount());
        assertEquals(7L, response.projectsCount());
    }

    @Test
    void listAllNarrowsToTheRequestedLevelAndType() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID parentId = UUID.randomUUID();
        Organization factory = organization(UUID.randomUUID(), Organization.OrganizationType.FACTORY, parentId, true);
        Organization line = organization(UUID.randomUUID(), Organization.OrganizationType.LINE, parentId, true);
        when(organizations.findAllForSelection(parentId, true)).thenReturn(List.of(factory, line));

        OrganizationService service = new OrganizationService(organizations, projects);

        // A cascading list asks for one level and one kind of unit at a time, so narrowing is the
        // point; a caller that narrows by neither must still get the whole level back.
        var factories = service.listAll(parentId, "FACTORY", true);
        assertEquals(1, factories.size());
        assertEquals(factory.getId(), factories.get(0).id());
        assertEquals("FACTORY", factories.get(0).type());

        assertEquals(2, service.listAll(parentId, null, true).size());
    }

    private Organization organization(UUID id,
                                      Organization.OrganizationType type,
                                      UUID parentId,
                                      boolean active) {
        Organization org = new Organization();
        org.setId(id);
        org.setType(type);
        org.setParentId(parentId);
        org.setName(type.name());
        org.setCode(type.name() + "-" + id.toString().substring(0, 8));
        org.setIsActive(active);
        return org;
    }
}
