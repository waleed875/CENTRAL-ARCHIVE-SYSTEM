package com.centralArchiveSystem.Archive.import_.migration.legacy;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSource;
import com.centralArchiveSystem.Archive.import_.migration.setting.MigrationSettingsService;
import com.centralArchiveSystem.Archive.import_.migration.setting.SettingsCrypto;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Several organisations run the same legacy ERD, so every one of their databases contains
 * tbl_side id 1, tbl_project id 23 and tbl_files id 5. Nothing may be shared between them: not the
 * organization code, not the project uid, not the source reference and not the deduplication key.
 * These tests pin that behaviour without a database, so they run on any machine.
 */
class LegacyMigrationServiceOfficeScopeTest {

    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final LegacyMigrationItemRepository itemRepository = mock(LegacyMigrationItemRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AtomicInteger organizationIds = new AtomicInteger();

    /** Stands in for the legacy_migration_items table so state carries across phases. */
    private final Map<String, LegacyMigrationItem> items = new HashMap<>();
    private final List<Project> savedProjects = new ArrayList<>();

    private final LegacyMigrationService service = new LegacyMigrationService(
            mock(Environment.class),
            mock(MinioService.class),
            mock(MinioConfig.class),
            organizationRepository,
            projectRepository,
            mock(FileRepository.class),
            mock(FileCategoryRepository.class),
            mock(FileTypeRepository.class),
            itemRepository,
            mock(MigrationSettingsService.class),
            new SettingsCrypto("test-encryption-key-for-legacy-office-scope"),
            new PassthroughTransactionManager());

    @BeforeEach
    void stubRepositories() {
        when(organizationRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization org = invocation.getArgument(0);
            org.setId(new UUID(organizationIds.incrementAndGet(), 0L));
            return org;
        });

        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(UUID.nameUUIDFromBytes(("target-" + project.getProjectUid()).getBytes()));
            savedProjects.add(project);
            return project;
        });
        when(projectRepository.saveAndFlush(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(UUID.nameUUIDFromBytes(("target-" + project.getProjectUid()).getBytes()));
            savedProjects.add(project);
            return project;
        });
        when(projectRepository.existsById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return savedProjects.stream().anyMatch(project -> id.equals(project.getId()));
        });
        // Mirrors the projects_organization_id_project_number_year_version_no_key constraint against
        // the rows this run has already created, so a duplicate number is detected the same way the
        // database would detect it.
        when(projectRepository.existsByOrganizationIdAndProjectNumberAndYearAndVersionNo(
                any(), any(), any(), any())).thenAnswer(invocation -> {
            UUID organizationId = invocation.getArgument(0);
            String projectNumber = invocation.getArgument(1);
            Integer year = invocation.getArgument(2);
            Integer versionNo = invocation.getArgument(3);
            return savedProjects.stream().anyMatch(project ->
                    organizationId.equals(project.getOrganizationId())
                            && projectNumber.equals(project.getProjectNumber())
                            && year.equals(project.getYear())
                            && versionNo.equals(project.getVersionNo()));
        });

        when(itemRepository.save(any(LegacyMigrationItem.class))).thenAnswer(invocation -> {
            LegacyMigrationItem item = invocation.getArgument(0);
            item.setId(UUID.randomUUID());
            items.put(key(item.getMigrationSourceId(), item.getKind(), item.getSourceKey()), item);
            return item;
        });
        when(itemRepository.findByMigrationSourceIdAndKindAndSourceKey(any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(items.get(
                        key(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
        when(itemRepository.existsByMigrationSourceIdAndKindAndSourceKey(any(), any(), any()))
                .thenAnswer(invocation -> items.containsKey(
                        key(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
    }

    private static String key(UUID source, LegacyMigrationItem.Kind kind, String sourceKey) {
        return source + "|" + kind + "|" + sourceKey;
    }

    @Test
    void twoOfficesWithTheSameLegacyIdsGetDistinctOrganizationsProjectsAndReferences() throws Exception {
        stubLegacyRows(1, 23, "P-23");

        var officeA = connection("ARC1");
        var officeB = connection("ARC2");

        assertEquals(1, service.migrateReferences(officeA));
        assertEquals(1, service.migrateReferences(officeB));
        assertEquals(1, service.migrateProjects(officeA));
        assertEquals(1, service.migrateProjects(officeB));

        // Both organizations exist even though both tbl_side rows carried id 1.
        ArgumentCaptor<Organization> orgCaptor = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository, times(2)).save(orgCaptor.capture());
        assertEquals(List.of("LEGACY-ARC1-1", "LEGACY-ARC2-1"),
                orgCaptor.getAllValues().stream().map(Organization::getCode).toList());

        // The same legacy project id in two offices must not share a uid or a reference.
        assertEquals(2, savedProjects.size());
        Project fromA = projectFrom("ARC1");
        Project fromB = projectFrom("ARC2");
        assertNotEquals(fromA.getProjectUid(), fromB.getProjectUid());
        assertEquals("ARC1:tbl_project:23", fromA.getSourceReference());
        assertEquals("ARC2:tbl_project:23", fromB.getSourceReference());

        // Each project hangs off its own office's organization, not a shared one.
        assertNotEquals(fromA.getOrganizationId(), fromB.getOrganizationId());
    }

    @Test
    void anAlreadyTrackedRowIsSkippedForItsOwnOfficeOnly() throws Exception {
        stubLegacyRows(1, 23, "P-23");

        var officeA = connection("ARC1");
        assertEquals(1, service.migrateReferences(officeA));
        assertEquals(1, service.migrateProjects(officeA));

        // Re-running the same office is a no-op, which is what makes a retry safe.
        assertEquals(0, service.migrateReferences(officeA));
        assertEquals(0, service.migrateProjects(officeA));
        assertEquals(1, savedProjects.size());

        // The other office still gets its own copy of project 23.
        var officeB = connection("ARC2");
        assertEquals(1, service.migrateReferences(officeB));
        assertEquals(1, service.migrateProjects(officeB));
        assertEquals(2, savedProjects.size());
    }

    @Test
    void staleDoneProjectMappingWithoutDatabaseRowGetsRecovered() throws Exception {
        stubLegacyRows(1, 23, "P-23");
        var office = connection("ARC1");
        assertEquals(1, service.migrateReferences(office));
        assertEquals(1, service.migrateProjects(office));
        assertEquals(1, savedProjects.size());
        // Simulate a tracking row pointing to a project deleted outside the migration.
        savedProjects.clear();
        assertEquals(1, service.migrateProjects(office));
        assertEquals(1, savedProjects.size());
    }

    @Test
    void duplicateProjectNumbersWithinOneOfficeArePreservedAsDistinctImportedProjects() throws Exception {
        // Legacy databases may reuse a project number for different records. Import must preserve
        // each source row rather than treating a collision as evidence that it is the same project.
        Map<String, Object> first = projectRow(1, 1, "6456", "4564");
        Map<String, Object> duplicate = projectRow(5, 1, "6456", "4564");
        Map<String, Object> other = projectRow(9, 2, "999", "2024");

        when(jdbc.queryForList(contains("tbl_project")))
                .thenReturn(List.of(first, duplicate, other));
        stubTwoSides();

        var office = connection("ARC1");
        assertEquals(2, service.migrateReferences(office));
        assertEquals(3, service.migrateProjects(office), "all source projects must be imported");

        assertEquals(3, savedProjects.size());
        assertEquals(3, items.values().stream()
                .filter(item -> item.getKind() == LegacyMigrationItem.Kind.PROJECT
                        && item.getSourceKey().startsWith("PROJECT-")
                        && item.getStatus() == LegacyMigrationItem.Status.DONE)
                .count());
        assertEquals(3, savedProjects.stream()
                .map(Project::getProjectUid)
                .distinct()
                .count());
        assertEquals(2, savedProjects.stream()
                .filter(project -> "6456".equals(project.getProjectNumber()))
                .count());
        assertTrue(items.values().stream().noneMatch(item ->
                item.getKind() == LegacyMigrationItem.Kind.PROJECT
                        && item.getStatus() == LegacyMigrationItem.Status.FAILED));
    }

    @Test
    void cancelledProjectPhaseDoesNotStartAnotherProject() throws Exception {
        stubTwoSides();
        when(jdbc.queryForList(contains("tbl_project"))).thenReturn(List.of(
                projectRow(41, 1, "41", "2024"),
                projectRow(42, 1, "42", "2024")));

        MigrationCancellationToken cancellation = new MigrationCancellationToken();
        cancellation.requestCancellation();

        int created = service.migrateProjects(connection("ARC1"), cancellation);

        assertEquals(0, created);
        assertTrue(savedProjects.isEmpty());
        assertTrue(items.values().stream().noneMatch(item ->
                item.getKind() == LegacyMigrationItem.Kind.PROJECT
                        && item.getSourceKey().startsWith("PROJECT-")));
    }

    @Test
    void aPreviouslyFailedProjectTrackingRowIsRetriedAndUpdatedInPlace() throws Exception {
        Map<String, Object> project = projectRow(42, 1, "3", "2010");
        when(jdbc.queryForList(contains("tbl_project"))).thenReturn(List.of(project));
        stubTwoSides();

        var office = connection("ARC1");
        LegacyMigrationItem failed = new LegacyMigrationItem();
        failed.setId(UUID.randomUUID());
        failed.setMigrationSourceId(office.sourceId());
        failed.setKind(LegacyMigrationItem.Kind.PROJECT);
        failed.setSourceKey("PROJECT-42");
        failed.setSourceId(42L);
        failed.setStatus(LegacyMigrationItem.Status.FAILED);
        failed.setAttempts(1);
        failed.setError("previous duplicate-number conflict");
        items.put(key(office.sourceId(), LegacyMigrationItem.Kind.PROJECT, "PROJECT-42"), failed);

        assertEquals(1, service.migrateProjects(office));
        LegacyMigrationItem recovered = items.get(
                key(office.sourceId(), LegacyMigrationItem.Kind.PROJECT, "PROJECT-42"));
        assertSame(failed, recovered);
        assertEquals(LegacyMigrationItem.Status.DONE, recovered.getStatus());
        assertNotNull(recovered.getTargetId());
        assertNull(recovered.getError());
        assertEquals(1, savedProjects.size());
    }

    private Map<String, Object> projectRow(int projectId, int sideId, String number, String year) {
        Map<String, Object> project = new HashMap<>();
        project.put("idproject", projectId);
        project.put("idside", sideId);
        project.put("name", "Project " + projectId);
        project.put("number", number);
        project.put("code", "C-" + projectId);
        project.put("year", year);
        return project;
    }

    private void stubTwoSides() {
        when(jdbc.queryForList(contains("tbl_side"))).thenReturn(List.of(
                sideRow(1, "Plant One"),
                sideRow(2, "Plant Two")));
    }

    private static Map<String, Object> sideRow(int sideId, String name) {
        Map<String, Object> side = new HashMap<>();
        side.put("idside", sideId);
        side.put("name", name);
        side.put("status", true);
        return side;
    }

    @Test
    void openingAConnectionRequiresAnOfficeName() {        MigrationSource source = new MigrationSource();
        source.setId(UUID.randomUUID());
        source.setName("No office");
        source.setDatabaseName("legacy_db");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service.openConnection(source));
        assertTrue(error.getMessage().contains("office name"));
    }

    private Project projectFrom(String office) {
        return savedProjects.stream()
                .filter(project -> project.getSourceReference().startsWith(office + ":"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no project migrated for office " + office));
    }

    private LegacyMigrationService.LegacyConnection connection(String office) {
        return new LegacyMigrationService.LegacyConnection(UUID.randomUUID(), office, office, jdbc, null);
    }

    private void stubLegacyRows(int sideId, int projectId, String projectNumber) {
        Map<String, Object> side = new HashMap<>();
        side.put("idside", sideId);
        side.put("name", "Central Plant");
        side.put("status", true);

        Map<String, Object> project = new HashMap<>();
        project.put("idproject", projectId);
        project.put("idside", sideId);
        project.put("name", "Shared Legacy Project");
        project.put("number", projectNumber);
        project.put("code", "C-" + projectId);
        project.put("year", "2024");
        project.put("admin", "Admin");
        project.put("detils", "details");
        project.put("note", "note");

        when(jdbc.queryForList(contains("tbl_side"))).thenReturn(List.of(side));
        when(jdbc.queryForList(contains("tbl_project"))).thenReturn(List.of(project));
    }

    /** Stand-in so the constructor receives a usable transaction manager. */
    private static final class PassthroughTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
