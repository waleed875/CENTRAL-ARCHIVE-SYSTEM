package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.migration.legacy.LegacyMigrationService;
import com.centralArchiveSystem.Archive.project.project.dto.IncompleteProjectReportResponse;
import com.centralArchiveSystem.Archive.project.project.dto.IncompleteProjectReportRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/** Rows AND aggregate counts are derived only from projects the current caller can read. */
@Service
public class IncompleteProjectReportService {
    private final IncompleteProjectQueryRepository projects;
    private final OrganizationRepository organizations;
    private final ConfidentialityAccessService confidentiality;

    public IncompleteProjectReportService(IncompleteProjectQueryRepository projects,
            OrganizationRepository organizations, ConfidentialityAccessService confidentiality) {
        this.projects = projects;
        this.organizations = organizations;
        this.confidentiality = confidentiality;
    }

    @Transactional(readOnly = true)
    public IncompleteProjectReportResponse buildReport() {
        List<Project> accessible = projects.findAccessibleIncompleteLatest(confidentiality.currentMaxLevelOrder());
        Map<UUID, String> names = organizations.findAllById(accessible.stream()
                .map(Project::getOrganizationId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(o -> o.getId(), o -> o.getName() == null ? "" : o.getName()));
        List<IncompleteProjectReportRow> rows = new ArrayList<>();
        Map<String, Long> fieldCounts = new HashMap<>();
        int unknownYears = 0;
        for (Project project : accessible) {
            List<String> missing = project.getMissingFields() == null ? List.of()
                    : Arrays.stream(project.getMissingFields().split(","))
                            .map(String::trim).filter(value -> !value.isEmpty()).toList();
            missing.forEach(field -> fieldCounts.merge(field, 1L, Long::sum));
            if (Integer.valueOf(LegacyMigrationService.UNKNOWN_YEAR).equals(project.getYear())) unknownYears++;
            rows.add(new IncompleteProjectReportRow(project.getId(), project.getProjectNumber(), project.getName(),
                    project.getYear(), names.get(project.getOrganizationId()),
                    project.getStatus() == null ? null : project.getStatus().name(),
                    project.getSourceType() == null ? null : project.getSourceType().name(),
                    project.getSourceReference(), missing, project.getNotes(),
                    project.getCreatedAt() == null ? null : project.getCreatedAt().toString()));
        }
        List<IncompleteProjectReportResponse.FieldCount> counts = fieldCounts.entrySet().stream()
                .map(e -> new IncompleteProjectReportResponse.FieldCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(IncompleteProjectReportResponse.FieldCount::count).reversed()
                        .thenComparing(IncompleteProjectReportResponse.FieldCount::field)).toList();
        return new IncompleteProjectReportResponse(rows.size(), unknownYears, rows, counts);
    }
}
