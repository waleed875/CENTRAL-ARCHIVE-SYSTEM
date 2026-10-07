package com.centralArchiveSystem.Archive.common.enums;

import com.centralArchiveSystem.Archive.config.AuditAspect;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.import_.migration.MigrationBatch;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectService;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable, additive catalogs. Dashboard activity and audit operations are distinct contracts. */
@Component
public class EnumCatalog {
    public static final String PROJECT_SORT_FIELDS = "projectSortFields";
    public static final String SORT_DIRECTIONS = "sortDirections";
    public static final String SORT_DIRECTION_ASC = "asc";
    public static final String SORT_DIRECTION_DESC = "desc";

    public Map<String, List<String>> values() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("projectStatus", names(Project.ProjectStatus.values()));
        values.put("dataCompletionStatus", names(Project.DataCompletionStatus.values()));
        values.put("projectSourceType", names(Project.SourceType.values()));
        values.put("userStatus", names(User.UserStatus.values()));
        values.put("organizationType", names(Organization.OrganizationType.values()));
        values.put("migrationSourceType", names(MigrationSourceType.values()));
        values.put("migrationBatchStatus", names(MigrationBatch.Status.values()));
        values.put("migrationBatchCompletionStatus", List.of(MigrationBatch.Status.SUCCESS.name(),
                MigrationBatch.Status.PARTIAL.name(), MigrationBatch.Status.FAILED.name()));
        values.put("packageStatus", names(BranchPackage.PackageStatus.values()));
        values.put("fileStatus", List.of("ACTIVE", "DELETED"));
        values.put("auditAction", AuditAspect.ACTIONS);
        values.put("auditEntityType", AuditAspect.ENTITY_TYPES);
        values.put(PROJECT_SORT_FIELDS, ProjectService.allowedSortFields());
        values.put(SORT_DIRECTIONS, List.of(SORT_DIRECTION_ASC, SORT_DIRECTION_DESC));
        List<String> activity = new ArrayList<>(List.of("PROJECT_CREATED", "PROJECT_IMPORTED",
                "PROJECT_SUBMITTED_FOR_REVIEW", "PROJECT_CORRECTION_REQUESTED", "PROJECT_REJECTED",
                "PROJECT_APPROVED", "PROJECT_ARCHIVED", "FILE_UPLOADED"));
        for (BranchPackage.PackageStatus status : BranchPackage.PackageStatus.values()) {
            activity.add("PACKAGE_" + status.name());
        }
        values.put("dashboardAction", List.copyOf(activity));
        values.put("dashboardEntityType", List.of("PROJECT", "FILE", "PACKAGE"));
        return values;
    }
    private static List<String> names(Enum<?>[] constants) {
        return java.util.Arrays.stream(constants).map(Enum::name).toList();
    }
}
