package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationService;
import com.centralArchiveSystem.Archive.project.project.dto.IncompleteProjectReportResponse;
import com.centralArchiveSystem.Archive.project.project.dto.IncompleteProjectReportRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only report over projects that were allowed into the platform with a substituted mandatory
 * field. Migrated projects are never rejected outright; this report is how the missing values get
 * found and filled in afterwards.
 */
@Service
public class IncompleteProjectReportService {

    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;

    public IncompleteProjectReportService(ProjectRepository projectRepository,
                                          OrganizationRepository organizationRepository) {
        this.projectRepository = projectRepository;
        this.organizationRepository = organizationRepository;
    }

    @Transactional(readOnly = true)
    public IncompleteProjectReportResponse buildReport() {
        List<Project> projects = projectRepository
                .findByDataCompletionStatusAndIsLatestVersionTrueOrderByCreatedAtDesc(
                        Project.DataCompletionStatus.INCOMPLETE);

        Map<UUID, String> organizationNames = organizationRepository
                .findAllById(projects.stream()
                        .map(Project::getOrganizationId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(o -> o.getId(),
                        o -> o.getName() == null ? "" : o.getName()));

        List<IncompleteProjectReportRow> rows = new ArrayList<>(projects.size());
        Map<String, Long> fieldCounts = new HashMap<>();
        int unknownYearCount = 0;

        for (Project project : projects) {
            List<String> missing = splitMissingFields(project.getMissingFields());

            for (String field : missing) {
                fieldCounts.merge(field, 1L, Long::sum);
            }
            if (Integer.valueOf(LegacyMigrationService.UNKNOWN_YEAR).equals(project.getYear())) {
                unknownYearCount++;
            }

            rows.add(new IncompleteProjectReportRow(
                    project.getId(),
                    project.getProjectNumber(),
                    project.getName(),
                    project.getYear(),
                    organizationNames.get(project.getOrganizationId()),
                    project.getStatus() == null ? null : project.getStatus().name(),
                    project.getSourceType() == null ? null : project.getSourceType().name(),
                    project.getSourceReference(),
                    missing,
                    project.getNotes(),
                    project.getCreatedAt() == null ? null : project.getCreatedAt().toString()));
        }

        List<IncompleteProjectReportResponse.FieldCount> counts = fieldCounts.entrySet().stream()
                .map(e -> new IncompleteProjectReportResponse.FieldCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(IncompleteProjectReportResponse.FieldCount::count).reversed()
                        .thenComparing(IncompleteProjectReportResponse.FieldCount::field))
                .toList();

        return new IncompleteProjectReportResponse(rows.size(), unknownYearCount, rows, counts);
    }

    private static List<String> splitMissingFields(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
