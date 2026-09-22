package com.centralArchiveSystem.Archive.project.filecategory;

import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileCategoryServiceTest {

    @Test
    void deleteSoftDeactivatesCategory() {
        FileCategoryRepository repository = mock(FileCategoryRepository.class);
        UUID id = UUID.randomUUID();

        FileCategory category = new FileCategory();
        category.setId(id);
        category.setCode("DOCUMENTS");
        category.setName("Documents");
        category.setIsActive(true);

        when(repository.findById(id)).thenReturn(Optional.of(category));
        when(repository.countActiveFilesByCategoryId(id)).thenReturn(0L);

        FileCategoryService service = new FileCategoryService(repository);
        service.delete(id);

        assertFalse(category.getIsActive());
        verify(repository).save(category);
        verify(repository, never()).delete(any());
    }

    @Test
    void cannotDeactivateCategoryWithActiveFiles() {
        FileCategoryRepository repository = mock(FileCategoryRepository.class);
        UUID id = UUID.randomUUID();

        FileCategory category = new FileCategory();
        category.setId(id);
        category.setCode("DOCUMENTS");
        category.setName("Documents");
        category.setIsActive(true);

        when(repository.findById(id)).thenReturn(Optional.of(category));
        when(repository.countActiveFilesByCategoryId(id)).thenReturn(2L);

        FileCategoryService service = new FileCategoryService(repository);

        assertThrows(IllegalStateException.class,
                () -> service.update(
                        id,
                        new FileCategoryUpdateRequest(null, null, false)
                ));
        verify(repository, never()).save(any());
    }

    @Test
    void patchKeepsCategoryCodeImmutable() {
        FileCategoryRepository repository = mock(FileCategoryRepository.class);
        UUID id = UUID.randomUUID();

        FileCategory category = new FileCategory();
        category.setId(id);
        category.setCode("DOCUMENTS");
        category.setName("Documents");
        category.setIsActive(true);

        when(repository.findById(id)).thenReturn(Optional.of(category));
        when(repository.save(category)).thenReturn(category);
        when(repository.countFilesByCategoryId(id)).thenReturn(0L);

        FileCategoryService service = new FileCategoryService(repository);
        var response = service.update(
                id,
                new FileCategoryUpdateRequest("Project Documents", "Updated", null)
        );

        assertEquals("DOCUMENTS", response.code());
        assertEquals("Project Documents", response.name());
    }
}
