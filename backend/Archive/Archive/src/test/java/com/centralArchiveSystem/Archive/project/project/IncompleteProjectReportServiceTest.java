package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationService;
import com.centralArchiveSystem.Archive.project.project.dto.IncompleteProjectReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IncompleteProjectReportServiceTest {

    private ProjectRepository projectRepository;
    private OrganizationRepository organizationRepository;
    private IncompleteProjectReportService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        service = new IncompleteProjectReportService(projectRepository, organizationRepository);
    }

    private Project project(String number, Integer year, String missingFields) {
        Project p = new Project();
        p.setId(UUID.randomUUID());
        p.setOrganizationId(UUID.randomUUID());
        p.setProjectNumber(number);
        p.setName("Project " + number);
        p.setYear(year);
        p.setMissingFields(missingFields);
        p.setStatus(Project.ProjectStatus.IMPORTED);
        p.setSourceType(Project.SourceType.MIGRATION);
        p.setSourceReference("ARC2:tbl_project:" + number);
        p.setDataCompletionStatus(Project.DataCompletionStatus.INCOMPLETE);
        p.setCreatedAt(LocalDateTime.of(2026, 9, 27, 12, 0));
        return p;
    }

    private void givenProjects(List<Project> projects) {
        when(projectRepository
                .findByDataCompletionStatusAndIsLatestVersionTrueOrderByCreatedAtDesc(
                        Project.DataCompletionStatus.INCOMPLETE))
                .thenReturn(projects);
        when(organizationRepository.findAllById(any())).thenReturn(List.of());
    }

    @Test
    void listsEveryIncompleteProjectWithItsMissingFields() {
        givenProjects(List.of(
                project("A", 2020, "year,name"),
                project("B", LegacyMigrationService.UNKNOWN_YEAR, "year,projectCode")));

        IncompleteProjectReportResponse report = service.buildReport();

        assertEquals(2, report.totalIncomplete());
        assertEquals(List.of("year", "name"), report.projects().get(0).missingFields());
        assertEquals(List.of("year", "projectCode"), report.projects().get(1).missingFields());
        assertEquals("MIGRATION", report.projects().get(0).sourceType());
    }

    @Test
    void countsHowOftenEachFieldIsMissingAcrossTheWholeMigration() {
        givenProjects(List.of(
                project("A", 2020, "year,name"),
                project("B", 2021, "year"),
                project("C", 2022, "year,name,description")));

        IncompleteProjectReportResponse report = service.buildReport();

        assertEquals(3, count(report, "year"));
        assertEquals(2, count(report, "name"));
        assertEquals(1, count(report, "description"));
        // The most frequent field is listed first so the UI can lead with it.
        assertEquals("year", report.fieldCounts().get(0).field());
    }

    @Test
    void theUnknownYearSentinelIsCountedSeparately() {
        givenProjects(List.of(
                project("A", LegacyMigrationService.UNKNOWN_YEAR, "year"),
                project("B", LegacyMigrationService.UNKNOWN_YEAR, "year"),
                project("C", 2020, "name")));

        IncompleteProjectReportResponse report = service.buildReport();

        assertEquals(3, report.totalIncomplete());
        assertEquals(2, report.totalWithUnknownYear());
    }

    @Test
    void aProjectWithNoRecordedMissingFieldsStillAppearsWithoutACrash() {
        givenProjects(List.of(project("A", 2020, null)));

        IncompleteProjectReportResponse report = service.buildReport();

        assertEquals(1, report.totalIncomplete());
        assertTrue(report.projects().get(0).missingFields().isEmpty());
        assertTrue(report.fieldCounts().isEmpty());
    }

    @Test
    void anEmptyMigrationProducesAnEmptyReport() {
        givenProjects(List.of());

        IncompleteProjectReportResponse report = service.buildReport();

        assertEquals(0, report.totalIncomplete());
        assertEquals(0, report.totalWithUnknownYear());
        assertTrue(report.projects().isEmpty());
        assertTrue(report.fieldCounts().isEmpty());
    }

    @Test
    void resolvesTheOwningOrganizationNameForEachRow() {
        Project p = project("A", 2020, "year");
        Organization org = new Organization();
        org.setId(p.getOrganizationId());
        org.setName("Central Company");
        when(projectRepository
                .findByDataCompletionStatusAndIsLatestVersionTrueOrderByCreatedAtDesc(
                        Project.DataCompletionStatus.INCOMPLETE))
                .thenReturn(List.of(p));
        when(organizationRepository.findAllById(any())).thenReturn(List.of(org));

        assertEquals("Central Company", service.buildReport().projects().get(0).organizationName());
    }

    @Test
    void aRowWhoseOrganizationCannotBeResolvedIsStillListed() {
        Project p = project("A", 2020, "year");
        p.setOrganizationId(null);
        when(projectRepository
                .findByDataCompletionStatusAndIsLatestVersionTrueOrderByCreatedAtDesc(
                        Project.DataCompletionStatus.INCOMPLETE))
                .thenReturn(List.of(p));
        when(organizationRepository.findAllById(any())).thenReturn(List.of());

        IncompleteProjectReportResponse report = service.buildReport();

        assertEquals(1, report.totalIncomplete());
        assertNull(report.projects().get(0).organizationName());
    }

    private static long count(IncompleteProjectReportResponse report, String field) {
        Optional<IncompleteProjectReportResponse.FieldCount> hit = report.fieldCounts().stream()
                .filter(c -> c.field().equals(field))
                .findFirst();
        return hit.map(IncompleteProjectReportResponse.FieldCount::count).orElse(0L);
    }
}
