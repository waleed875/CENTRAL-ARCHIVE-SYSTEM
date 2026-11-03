package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IncompleteProjectReportServiceTest {
    private final IncompleteProjectQueryRepository projects = mock(IncompleteProjectQueryRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final ConfidentialityAccessService confidentiality = mock(ConfidentialityAccessService.class);
    private final IncompleteProjectReportService service = new IncompleteProjectReportService(projects, organizations, confidentiality);

    private Project project(int year, String missing) {
        Project p = new Project(); p.setId(UUID.randomUUID()); p.setOrganizationId(UUID.randomUUID());
        p.setProjectNumber("A"); p.setName("مشروع"); p.setYear(year); p.setMissingFields(missing);
        p.setStatus(Project.ProjectStatus.IMPORTED); p.setSourceType(Project.SourceType.MIGRATION);
        p.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0)); return p;
    }
    private void given(List<Project> values) {
        when(confidentiality.currentMaxLevelOrder()).thenReturn(2);
        when(projects.findAccessibleIncompleteLatest(2)).thenReturn(values);
        when(organizations.findAllById(any())).thenReturn(List.of());
    }
    @Test void queriesWithTheCurrentClearanceBeforeCountingOrResolvingNames() {
        given(List.of(project(2026, "name,year"), project(1000, "year")));
        var report = service.buildReport();
        assertEquals(2, report.totalIncomplete()); assertEquals(1, report.totalWithUnknownYear());
        assertEquals("year", report.fieldCounts().getFirst().field());
        assertEquals(2L, report.fieldCounts().getFirst().count());
        verify(projects).findAccessibleIncompleteLatest(2);
    }
    @Test void anEmptyAccessibleSetRevealsNoHiddenCounts() {
        given(List.of()); var report = service.buildReport();
        assertEquals(0, report.totalIncomplete()); assertTrue(report.projects().isEmpty());
        assertTrue(report.fieldCounts().isEmpty());
    }
    @Test void nullMissingFieldsRemainSupported() {
        given(List.of(project(2026, null)));
        assertTrue(service.buildReport().projects().getFirst().missingFields().isEmpty());
    }
    @Test void resolvesOrganizationNamesOnlyForAccessibleProjects() {
        Project p = project(2026, "name"); given(List.of(p));
        Organization org = new Organization(); org.setId(p.getOrganizationId()); org.setName("المؤسسة");
        when(organizations.findAllById(any())).thenReturn(List.of(org));
        assertEquals("المؤسسة", service.buildReport().projects().getFirst().organizationName());
    }
    @Test void unresolvedOrganizationDoesNotCrashTheReport() {
        Project p = project(2026, "name"); p.setOrganizationId(null); given(List.of(p));
        assertNull(service.buildReport().projects().getFirst().organizationName());
    }
    @Test void missingFieldsAreTrimmedAndEmptyEntriesAreDiscarded() {
        given(List.of(project(2026, " year, ,name ")));
        assertEquals(List.of("year", "name"), service.buildReport().projects().getFirst().missingFields());
    }
    @Test void noClearanceIsPassedAsNullRatherThanElevatedToAdministratorClearance() {
        when(confidentiality.currentMaxLevelOrder()).thenReturn(null);
        when(projects.findAccessibleIncompleteLatest(null)).thenReturn(List.of());
        service.buildReport(); verify(projects).findAccessibleIncompleteLatest(null);
    }
}
