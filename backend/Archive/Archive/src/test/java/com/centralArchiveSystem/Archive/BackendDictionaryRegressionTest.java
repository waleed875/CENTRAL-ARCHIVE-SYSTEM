package com.centralArchiveSystem.Archive;

import com.centralArchiveSystem.Archive.common.enums.EnumCatalog;
import com.centralArchiveSystem.Archive.import_.package_.BranchPackage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BackendDictionaryRegressionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EnumCatalog catalog;

    @Test
    void audioDictionaryHasAnActiveCategoryAndType() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM project.file_types t
                JOIN project.file_categories c ON c.id=t.category_id
                WHERE c.code='AUDIO' AND t.code='MEDIA-AUDIO' AND c.is_active AND t.is_active
                """, Integer.class);
        assertEquals(1, count);
    }

    @Test
    void dashboardVocabularyDoesNotReplaceTheExistingAuditContract() {
        var values = catalog.values();
        assertTrue(values.get("auditAction").contains("READ"));
        assertTrue(values.get("auditEntityType").contains("Project"));
        assertTrue(values.get("dashboardAction").contains("FILE_UPLOADED"));
        assertTrue(values.get("dashboardEntityType").contains("PACKAGE"));
        for (var status : BranchPackage.PackageStatus.values()) {
            assertTrue(values.get("dashboardAction").contains("PACKAGE_" + status.name()));
        }
    }

    /** Exercise the shipped SQL on old-shaped rows, not only its schema-history entry. */
    @Test
    @Transactional
    void historicalRejectionBackfillPreservesNotesAndExplicitReasonsAndIsIdempotent() throws Exception {
        UUID organization = UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations (id,code,name,type,is_active) VALUES (?,?,?,'COMPANY',true)",
                organization, "BACKFILL-" + organization, "Backfill regression");
        UUID oldRejected = historicalProject(organization, "REJECTED", "old rejection reason", null);
        UUID alreadySeparated = historicalProject(organization, "REJECTED", "correction instruction", "explicit reason");
        UUID stillInReview = historicalProject(organization, "UNDER_REVIEW", "correction instruction", null);
        String sql = new String(new ClassPathResource(
                "db/migration/V24__preserve_historical_rejection_reasons.sql").getContentAsByteArray(),
                StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 2; attempt++) {
            jdbc.execute(sql);
            assertEquals("old rejection reason", reason(oldRejected));
            assertEquals("old rejection reason", jdbc.queryForObject(
                    "SELECT review_notes FROM project.projects WHERE id=?", String.class, oldRejected));
            assertEquals("explicit reason", reason(alreadySeparated));
            assertEquals("correction instruction", jdbc.queryForObject(
                    "SELECT review_notes FROM project.projects WHERE id=?", String.class, alreadySeparated));
            assertNull(reason(stillInReview));
        }
        // All fixtures and changes roll back with the test. Audit retention is not bypassed.
    }

    private UUID historicalProject(UUID organization, String status, String notes, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project.projects
                (id,project_uid,version_no,is_latest_version,organization_id,project_number,name,year,
                 status,review_notes,rejection_reason,source_type,data_completion_status)
                VALUES (?,?,1,true,?,?,?,2026,?,?,?,'MANUAL','COMPLETE')
                """, id, UUID.randomUUID(), organization, "BACKFILL-" + id,
                "Historical rejection regression", status, notes, reason);
        return id;
    }

    private String reason(UUID id) {
        return jdbc.queryForObject("SELECT rejection_reason FROM project.projects WHERE id=?", String.class, id);
    }
}
