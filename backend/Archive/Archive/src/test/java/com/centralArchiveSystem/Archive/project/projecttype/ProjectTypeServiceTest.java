package com.centralArchiveSystem.Archive.project.projecttype;

import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectTypeServiceTest {

    @Test
    void deleteSoftDeactivatesProjectType() {
        ProjectTypeRepository types = mock(ProjectTypeRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID id = UUID.randomUUID();
        ProjectType type = projectType(id);
        when(types.findById(id)).thenReturn(Optional.of(type));

        ProjectTypeService service = new ProjectTypeService(types, projects);
        service.delete(id);

        assertFalse(type.getIsActive());
        verify(types).save(type);
        verify(types, never()).delete(any());
    }

    @Test
    void patchKeepsCodeImmutableAndReturnsProjectCount() {
        ProjectTypeRepository types = mock(ProjectTypeRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        UUID id = UUID.randomUUID();
        ProjectType type = projectType(id);
        when(types.findById(id)).thenReturn(Optional.of(type));
        when(types.save(type)).thenReturn(type);
        when(projects.countLatestByProjectTypeId(id)).thenReturn(12L);

        ProjectTypeService service = new ProjectTypeService(types, projects);
        var response = service.update(
                id,
                new ProjectTypeUpdateRequest("Infrastructure Projects", "Updated", null)
        );

        assertEquals("INFRASTRUCTURE", response.code());
        assertEquals("Infrastructure Projects", response.name());
        assertEquals(12L, response.projectsCount());
    }

    private ProjectType projectType(UUID id) {
        ProjectType type = new ProjectType();
        type.setId(id);
        type.setCode("INFRASTRUCTURE");
        type.setName("Infrastructure");
        type.setDescription("Projects");
        type.setIsActive(true);
        return type;
    }
}
