package com.centralArchiveSystem.Archive.project.filetype;

import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeResponse;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeUpdateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileTypeServiceTest {

    @Test
    void deleteSoftDeactivatesFileType() {
        FileTypeRepository types = mock(FileTypeRepository.class);
        FileCategoryRepository categories = mock(FileCategoryRepository.class);
        UUID id = UUID.randomUUID();

        FileType type = fileType(id);
        when(types.findById(id)).thenReturn(Optional.of(type));
        when(types.countActiveFilesByTypeId(id)).thenReturn(0L);

        FileTypeService service = new FileTypeService(types, categories);
        service.delete(id);

        assertFalse(type.getIsActive());
        verify(types).save(type);
        verify(types, never()).delete(any());
    }

    @Test
    void cannotDeactivateFileTypeWithActiveFiles() {
        FileTypeRepository types = mock(FileTypeRepository.class);
        FileCategoryRepository categories = mock(FileCategoryRepository.class);
        UUID id = UUID.randomUUID();

        FileType type = fileType(id);
        when(types.findById(id)).thenReturn(Optional.of(type));
        when(types.countActiveFilesByTypeId(id)).thenReturn(1L);

        FileTypeService service = new FileTypeService(types, categories);

        assertThrows(IllegalStateException.class,
                () -> service.update(
                        id,
                        new FileTypeUpdateRequest(null, null, null, false)
                ));
        verify(types, never()).save(any());
    }

    @Test
    void patchKeepsCodeAndCategoryImmutable() {
        FileTypeRepository types = mock(FileTypeRepository.class);
        FileCategoryRepository categories = mock(FileCategoryRepository.class);
        UUID id = UUID.randomUUID();

        FileType type = fileType(id);
        UUID originalCategoryId = type.getCategoryId();

        when(types.findById(id)).thenReturn(Optional.of(type));
        when(types.save(type)).thenReturn(type);
        when(types.countFilesByTypeId(id)).thenReturn(0L);

        FileTypeService service = new FileTypeService(types, categories);
        var response = service.update(
                id,
                new FileTypeUpdateRequest("PDF document", "pdf", "Updated", null)
        );

        assertEquals("PDF", response.code());
        assertEquals(originalCategoryId, response.categoryId());
        assertEquals("PDF document", response.name());
    }

    @Test
    void listAllIsUnpaginatedAcrossEveryCategory() {
        FileTypeRepository types = mock(FileTypeRepository.class);
        FileCategoryRepository categories = mock(FileCategoryRepository.class);

        FileType pdf = fileType(UUID.randomUUID());
        FileType sheet = fileType(UUID.randomUUID());
        sheet.setCode("XLSX");
        sheet.setName("XLSX");

        when(types.findAllForSelection(true)).thenReturn(List.of(pdf, sheet));

        FileTypeService service = new FileTypeService(types, categories);
        var all = service.listAll(true);

        // Deliberately not narrowed to one category: this call is for the form that has not chosen
        // a category yet, so every type has to be offerable at once.
        assertEquals(List.of("PDF", "XLSX"),
                all.stream().map(FileTypeResponse::code).toList());
        verify(types, never()).searchFiltered(any(), any(), any(), any());
    }

    private FileType fileType(UUID id) {
        FileType type = new FileType();
        type.setId(id);
        type.setCategoryId(UUID.randomUUID());
        type.setCode("PDF");
        type.setName("PDF");
        type.setDefaultExtension("pdf");
        type.setIsActive(true);
        return type;
    }
}
