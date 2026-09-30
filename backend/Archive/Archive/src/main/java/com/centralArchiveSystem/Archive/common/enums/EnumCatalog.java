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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every set of values the API accepts for a field that is not free text, in one response.
 *
 * <p>The client needs these because most of them are Java enums that a hand-written client cannot
 * see. Without them it either hardcodes a copy that drifts on the first backend change, or leaves
 * the field as a text box where a mistyped value is rejected only after the form is submitted.
 * Reading the enums here rather than restating them means this cannot disagree with the code: a new
 * constant appears in the response the next time the application starts.
 *
 * <p>Keys are stable and part of the contract, because the client indexes on them.
 */
@Component
public class EnumCatalog {

    /**
     * Sort fields a project search accepts, and the directions it accepts.
     *
     * <p>A field outside this list is not rejected -- the service falls back to {@code createdAt} and
     * {@code desc} -- so these are advertised to keep the client from producing that silent
     * reordering, not to document a validation that exists.
     */
    public static final String PROJECT_SORT_FIELDS = "projectSortFields";
    public static final String SORT_DIRECTIONS = "sortDirections";
    public static final String SORT_DIRECTION_ASC = "asc";
    public static final String SORT_DIRECTION_DESC = "desc";

    private static final String PROJECT_STATUS = "projectStatus";
    private static final String DATA_COMPLETION_STATUS = "dataCompletionStatus";
    private static final String PROJECT_SOURCE_TYPE = "projectSourceType";
    private static final String USER_STATUS = "userStatus";
    private static final String ORGANIZATION_TYPE = "organizationType";
    private static final String MIGRATION_SOURCE_TYPE = "migrationSourceType";
    private static final String MIGRATION_BATCH_STATUS = "migrationBatchStatus";
    private static final String MIGRATION_BATCH_COMPLETION_STATUS = "migrationBatchCompletionStatus";
    private static final String PACKAGE_STATUS = "packageStatus";
    private static final String FILE_STATUS = "fileStatus";
    private static final String AUDIT_ACTION = "auditAction";
    private static final String AUDIT_ENTITY_TYPE = "auditEntityType";

    /**
     * The values a migration batch may be completed with, as opposed to the states it can sit in.
     * Exposed separately because the batch screen needs both and a client that offers PLANNED or
     * RUNNING on the completion control gets a 400 after submitting.
     */
    private static final List<MigrationBatch.Status> COMPLETION_STATUSES = List.of(
            MigrationBatch.Status.SUCCESS,
            MigrationBatch.Status.PARTIAL,
            MigrationBatch.Status.FAILED);

    public Map<String, List<String>> values() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put(PROJECT_STATUS, names(Project.ProjectStatus.values()));
        values.put(DATA_COMPLETION_STATUS, names(Project.DataCompletionStatus.values()));
        values.put(PROJECT_SOURCE_TYPE, names(Project.SourceType.values()));
        values.put(USER_STATUS, names(User.UserStatus.values()));
        values.put(ORGANIZATION_TYPE, names(Organization.OrganizationType.values()));
        values.put(MIGRATION_SOURCE_TYPE, names(MigrationSourceType.values()));
        values.put(MIGRATION_BATCH_STATUS, names(MigrationBatch.Status.values()));
        values.put(MIGRATION_BATCH_COMPLETION_STATUS, names(COMPLETION_STATUSES.toArray(new MigrationBatch.Status[0])));
        values.put(PACKAGE_STATUS, names(BranchPackage.PackageStatus.values()));
        values.put(FILE_STATUS, List.of("ACTIVE", "DELETED"));
        values.put(AUDIT_ACTION, AuditAspect.ACTIONS);
        values.put(AUDIT_ENTITY_TYPE, AuditAspect.ENTITY_TYPES);
        values.put(PROJECT_SORT_FIELDS, ProjectService.allowedSortFields());
        values.put(SORT_DIRECTIONS, List.of(SORT_DIRECTION_ASC, SORT_DIRECTION_DESC));
        return values;
    }

    private static List<String> names(Enum<?>[] constants) {
        return java.util.Arrays.stream(constants).map(Enum::name).toList();
    }
}
