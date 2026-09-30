package com.centralArchiveSystem.Archive.core.confidentiality;

import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelResponse;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelUpdateRequest;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
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

    @Test
    void listAllBypassesPaginationAndKeepsSeverityOrder() {
        ConfidentialityLevelRepository levels = mock(ConfidentialityLevelRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        FileRepository files = mock(FileRepository.class);
        UserRepository users = mock(UserRepository.class);

        when(levels.findAllByOrderByLevelOrderAsc())
                .thenReturn(List.of(level(UUID.randomUUID(), 1), level(UUID.randomUUID(), 2),
                        level(UUID.randomUUID(), 3)));

        ConfidentialityLevelService service =
                new ConfidentialityLevelService(levels, projects, files, users);

        var all = service.listAll();

        // A page boundary inside a dropdown would drop a level the user was entitled to choose,
        // and nothing on screen would say which one went missing.
        verify(levels, never()).findAll(any(Pageable.class));
        assertEquals(List.of(1, 2, 3), all.stream()
                .map(ConfidentialityLevelResponse::levelOrder)
                .toList());
    }

    private ConfidentialityLevel level(UUID id) {
        return level(id, 3);
    }

    private ConfidentialityLevel level(UUID id, int levelOrder) {
        ConfidentialityLevel level = new ConfidentialityLevel();
        level.setId(id);
        level.setCode("SECRET");
        level.setName("Secret");
        level.setLevelOrder(levelOrder);
        level.setDescription("Secret level");
        return level;
    }
}
