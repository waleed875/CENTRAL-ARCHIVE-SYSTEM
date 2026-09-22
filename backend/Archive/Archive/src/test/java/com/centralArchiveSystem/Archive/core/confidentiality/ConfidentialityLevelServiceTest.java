package com.centralArchiveSystem.Archive.core.confidentiality;

import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelUpdateRequest;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfidentialityLevelServiceTest {

    @Test
    void responseIncludesUsersCountAndPatchKeepsCodeImmutable() {
        ConfidentialityLevelRepository levels = mock(ConfidentialityLevelRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        FileRepository files = mock(FileRepository.class);
        UserRepository users = mock(UserRepository.class);

        UUID id = UUID.randomUUID();
        ConfidentialityLevel level = level(id);
        when(levels.findById(id)).thenReturn(Optional.of(level));
        when(levels.save(level)).thenReturn(level);
        when(projects.countByConfidentialityLevelId(id)).thenReturn(3L);
        when(files.countByConfidentialityLevelId(id)).thenReturn(4L);
        when(users.countByMaxConfidentialityLevelId(id)).thenReturn(5L);

        ConfidentialityLevelService service =
                new ConfidentialityLevelService(levels, projects, files, users);

        var response = service.update(
                id,
                new ConfidentialityLevelUpdateRequest("Restricted", 7, "Updated")
        );

        assertEquals("SECRET", response.code());
        assertEquals("Restricted", response.name());
        assertEquals(7, response.levelOrder());
        assertEquals(3L, response.projectsCount());
        assertEquals(4L, response.filesCount());
        assertEquals(5L, response.usersCount());
    }

    @Test
    void deleteRejectsLevelReferencedByUsers() {
        ConfidentialityLevelRepository levels = mock(ConfidentialityLevelRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        FileRepository files = mock(FileRepository.class);
        UserRepository users = mock(UserRepository.class);

        UUID id = UUID.randomUUID();
        ConfidentialityLevel level = level(id);
        when(levels.findById(id)).thenReturn(Optional.of(level));
        when(projects.countByConfidentialityLevelId(id)).thenReturn(0L);
        when(files.countByConfidentialityLevelId(id)).thenReturn(0L);
        when(users.countByMaxConfidentialityLevelId(id)).thenReturn(2L);

        ConfidentialityLevelService service =
                new ConfidentialityLevelService(levels, projects, files, users);

        assertThrows(IllegalStateException.class, () -> service.delete(id));
        verify(levels, never()).delete(any());
    }

    @Test
    void deleteRejectsHistoricalFileReferencesToo() {
        ConfidentialityLevelRepository levels = mock(ConfidentialityLevelRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        FileRepository files = mock(FileRepository.class);
        UserRepository users = mock(UserRepository.class);

        UUID id = UUID.randomUUID();
        ConfidentialityLevel level = level(id);
        when(levels.findById(id)).thenReturn(Optional.of(level));
        when(projects.countByConfidentialityLevelId(id)).thenReturn(0L);
        when(files.countByConfidentialityLevelId(id)).thenReturn(1L);

        ConfidentialityLevelService service =
                new ConfidentialityLevelService(levels, projects, files, users);

        assertThrows(IllegalStateException.class, () -> service.delete(id));
        verify(levels, never()).delete(any());
        verify(users, never()).countByMaxConfidentialityLevelId(any());
    }

    private ConfidentialityLevel level(UUID id) {
        ConfidentialityLevel level = new ConfidentialityLevel();
        level.setId(id);
        level.setCode("SECRET");
        level.setName("Secret");
        level.setLevelOrder(3);
        level.setDescription("Secret level");
        return level;
    }
}
