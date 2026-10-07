package com.centralArchiveSystem.Archive.common.enums;

import com.centralArchiveSystem.Archive.config.AuditAspect;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.import_.migration.MigrationBatch;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog exists so a client can build a dropdown without keeping its own copy of these values.
 * That only holds while the response matches the code, so these tests read the enums back rather
 * than pasting the expected lists in: a constant added to an enum has to appear here too.
 */
class EnumCatalogTest {

    private final EnumCatalog catalog = new EnumCatalog();

    @Test
    void readsEveryProjectEnum() {
        Map<String, List<String>> values = catalog.values();
        assertEquals(names(Project.ProjectStatus.values()), values.get("projectStatus"));
        assertEquals(names(Project.DataCompletionStatus.values()), values.get("dataCompletionStatus"));
        assertEquals(names(Project.SourceType.values()), values.get("projectSourceType"));
    }

    @Test
    void readsEveryUserAndOrganizationEnum() {
        Map<String, List<String>> values = catalog.values();
        assertEquals(names(User.UserStatus.values()), values.get("userStatus"));
        assertEquals(names(Organization.OrganizationType.values()), values.get("organizationType"));
    }

    @Test
    void readsTheMigrationAndPackageEnums() {
        Map<String, List<String>> values = catalog.values();
        assertEquals(names(MigrationSourceType.values()), values.get("migrationSourceType"));
        assertEquals(names(MigrationBatch.Status.values()), values.get("migrationBatchStatus"));
        assertEquals(names(BranchPackage.PackageStatus.values()), values.get("packageStatus"));
    }

    /** Completing a batch accepts only the three terminal states. */
    @Test
    void completionStatusOffersOnlyTheStatesTheServiceAccepts() {
        List<String> completion = catalog.values().get("migrationBatchCompletionStatus");
        assertEquals(List.of(MigrationBatch.Status.SUCCESS.name(),
                MigrationBatch.Status.PARTIAL.name(), MigrationBatch.Status.FAILED.name()), completion);
        assertFalse(completion.contains(MigrationBatch.Status.PLANNED.name()));
        assertFalse(completion.contains(MigrationBatch.Status.RUNNING.name()));
    }

    @Test
    void publishesTheProjectSortFieldsTheServiceActuallyHonours() {
        List<String> fields = catalog.values().get("projectSortFields");
        assertEquals(ProjectService.allowedSortFields(), fields);
        assertTrue(fields.contains("createdAt"));
        assertTrue(fields.contains("projectNumber"));
        assertTrue(fields.contains("submittedAt"));
    }

    @Test
    void publishesBothSortDirections() {
        assertEquals(List.of("asc", "desc"), catalog.values().get("sortDirections"));
    }

    @Test
    void publishesTheFileStatusValues() {
        assertEquals(List.of("ACTIVE", "DELETED"), catalog.values().get("fileStatus"));
    }

    @Test
    void theAuditFiltersCoverEveryActionTheAspectCanRecord() {
        Set<String> published = Set.copyOf(catalog.values().get("auditAction"));
        Set<String> recorded = Set.copyOf(AuditAspect.ACTIONS);
        assertEquals(recorded, published);
        assertTrue(published.containsAll(List.of("CREATE", "UPDATE", "DELETE", "READ", "IMPORT",
                "APPROVE", "REJECT", "ARCHIVE", "EXECUTE")));
    }

    @Test
    void theAuditEntityTypeFilterCoversEveryAuditedController() {
        List<String> types = catalog.values().get("auditEntityType");
        assertTrue(types.containsAll(List.of("User", "Role", "Organization", "ConfidentialityLevel",
                "Project", "ProjectMember", "ProjectType", "File", "FileCategory", "BranchPackage",
                "Migration", "AuditLog")), types.toString());
        assertTrue(types.stream().noneMatch(type -> type.endsWith("Controller")),
                "entity types are the controller name without its suffix: " + types);
    }

    /** Retain exact-key validation; the two new dashboard keys are an explicit additive contract. */
    @Test
    void publishesExactlyTheAgreedKeys() {
        assertEquals(Set.of("projectStatus", "dataCompletionStatus", "projectSourceType", "userStatus",
                "organizationType", "migrationSourceType", "migrationBatchStatus",
                "migrationBatchCompletionStatus", "packageStatus", "fileStatus", "auditAction",
                "auditEntityType", "projectSortFields", "sortDirections", "dashboardAction",
                "dashboardEntityType"), Set.copyOf(catalog.values().keySet()));
    }

    @Test
    void dashboardCatalogIncludesEveryProjectFileAndPackageEventWithoutDuplicates() {
        List<String> actions = catalog.values().get("dashboardAction");
        Set<String> expected = new java.util.HashSet<>(List.of("PROJECT_CREATED", "PROJECT_IMPORTED",
                "PROJECT_SUBMITTED_FOR_REVIEW", "PROJECT_CORRECTION_REQUESTED", "PROJECT_REJECTED",
                "PROJECT_APPROVED", "PROJECT_ARCHIVED", "FILE_UPLOADED"));
        for (BranchPackage.PackageStatus status : BranchPackage.PackageStatus.values()) {
            expected.add("PACKAGE_" + status.name());
        }
        assertEquals(expected, Set.copyOf(actions));
        assertEquals(expected.size(), actions.size());
        assertEquals(List.of("PROJECT", "FILE", "PACKAGE"), catalog.values().get("dashboardEntityType"));
        assertFalse(catalog.values().get("auditAction").contains("FILE_UPLOADED"));
    }

    @Test
    void noPublishedSetIsEmpty() {
        catalog.values().forEach((key, values) -> assertFalse(values.isEmpty(), key + " is empty"));
    }

    private static List<String> names(Enum<?>[] constants) {
        return java.util.Arrays.stream(constants).map(Enum::name).toList();
    }
}
