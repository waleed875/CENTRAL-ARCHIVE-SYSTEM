package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlobalFileSearchServiceTest {
    private final FileRepository repository = mock(FileRepository.class);
    private final ConfidentialityAccessService clearance = mock(ConfidentialityAccessService.class);
    private final GlobalFileSearchService service = new GlobalFileSearchService(repository, clearance);

    @Test
    void wildcardCharactersAreLiteralAndNotProjectNameSearch() {
        assertEquals("abc!%!_!!", GlobalFileSearchService.escapeLike("abc%_!"));
        when(clearance.currentMaxLevelOrder()).thenReturn(2);
        when(repository.searchGlobalFileNames(
                anyString(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(false), eq(2), eq("newest"),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service.search("Report%_!", null, null, null, null, null, null, null,
                false, "newest", 1, 20);
        verify(repository).searchGlobalFileNames(
                eq("%report!%!_!!%"), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(false), eq(2),
                eq("newest"), any(Pageable.class));
    }

    @Test
    void combinesFiltersWithDateBoundsAndClampsPageSize() {
        UUID cat = UUID.randomUUID(), org = UUID.randomUUID();
        when(clearance.currentMaxLevelOrder()).thenReturn(null);
        service.search("وثيقة", ".PDF", cat, org, "P_25", 2025,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                true, "oldest", 3, 900);
        verify(repository).searchGlobalFileNames(eq("%وثيقة%"),
                eq("pdf"), eq("%.pdf"), eq(cat), eq(org), eq("%p!_25%"),
                eq(2025), eq(LocalDate.of(2025,1,1).atStartOfDay()),
                eq(LocalDate.of(2026,1,1).atStartOfDay()), eq(true),
                isNull(), eq("oldest"),
                argThat((Pageable p) -> p.getPageNumber() == 2 && p.getPageSize() == 100));
    }

    @Test
    void rejectsInvalidFiltersBeforeExecutingAnyQuery() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search(null,null,null,null,null,null,null,null,false,"newest",0,20));
        assertThrows(IllegalArgumentException.class, () ->
                service.search(null,"pdf' OR 1=1",null,null,null,null,null,null,false,"newest",1,20));
        assertThrows(IllegalArgumentException.class, () ->
                service.search("x".repeat(151),null,null,null,null,null,null,null,false,"newest",1,20));
        assertThrows(IllegalArgumentException.class, () ->
                service.search(null,null,null,null,null,null,LocalDate.of(2026,5,2),
                        LocalDate.of(2026,5,1),false,"newest",1,20));
        assertThrows(IllegalArgumentException.class, () ->
                service.search(null,null,null,null,null,null,null,null,false,"bad",1,20));
        verifyNoInteractions(repository);
    }
}
