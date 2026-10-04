package com.centralArchiveSystem.Archive;

import com.centralArchiveSystem.Archive.audit.AuditLog;
import com.centralArchiveSystem.Archive.audit.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PostgresSchemaIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Test
    void runsAgainstPostgresqlAndAppliesLatestFlywayMigration() {
        String version = jdbcTemplate.queryForObject(
                "SELECT version()",
                String.class
        );
        assertNotNull(version);
        assertTrue(version.startsWith("PostgreSQL"));

        Integer latestMigration = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM flyway_schema_history
                 WHERE version = '15'
                   AND success = true
                """,
                Integer.class
        );
        assertEquals(1, latestMigration);
    }

    @Test
    void productionSearchIndexesAndPgTrgmExist() {
        Integer extensionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'",
                Integer.class
        );
        assertEquals(1, extensionCount);

        Integer indexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_indexes
                 WHERE schemaname = 'project'
                   AND indexname IN (
                       'idx_projects_name_trgm',
                       'idx_projects_number_trgm',
                       'idx_projects_code_trgm',
                       'idx_projects_description_trgm',
                       'idx_projects_keywords_trgm',
                       'idx_projects_latest_org_year_status',
                       'idx_projects_uid_version_desc',
                       'idx_projects_confidentiality_latest',
                       'idx_files_original_name_trgm',
                       'idx_files_extension_trgm',
                       'idx_files_project_active_filters',
                       'idx_files_confidentiality_active'
                   )
                """,
                Integer.class
        );
        assertEquals(12, indexCount);
    }

    @Test
    void criticalSchemaColumnsUseExpectedDatabaseTypes() {
        String projectUidType = jdbcTemplate.queryForObject(
                """
                SELECT data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'project'
                   AND table_name = 'projects'
                   AND column_name = 'project_uid'
                """,
                String.class
        );
        assertEquals("uuid", projectUidType);

        String packageHashType = jdbcTemplate.queryForObject(
                """
                SELECT data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'import'
                   AND table_name = 'branch_packages'
                   AND column_name = 'package_hash'
                """,
                String.class
        );
        assertEquals("character varying", packageHashType);

        String tokenCutoffType = jdbcTemplate.queryForObject(
                """
                SELECT data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'core'
                   AND table_name = 'users'
                   AND column_name = 'token_valid_after'
                """,
                String.class
        );
        assertEquals("timestamp with time zone", tokenCutoffType);
    }

    @Test
    void migrationBatchNumberUsesStringTypeAndPerSourceUniqueness() {
        String batchNumberType = jdbcTemplate.queryForObject(
                """
                SELECT data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'import'
                   AND table_name = 'migration_batches'
                   AND column_name = 'batch_number'
                """,
                String.class
        );
        assertEquals("character varying", batchNumberType);

        Integer constraintCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'import'
                   AND t.relname = 'migration_batches'
                   AND c.conname = 'uk_migration_batches_source_batch_number'
                   AND c.contype = 'u'
                """,
                Integer.class
        );
        assertEquals(1, constraintCount);
    }


    @Test
    void importOperationRecoveryMetadataExists() {
        Map<String, String> types = jdbcTemplate.query(
                """
                SELECT column_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'import'
                   AND table_name = 'import_operations'
                   AND column_name IN ('last_heartbeat_at', 'request_snapshot', 'retry_of')
                """,
                rs -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("column_name"), rs.getString("data_type"));
                    }
                    return result;
                }
        );

        assertEquals("timestamp with time zone", types.get("last_heartbeat_at"));
        assertEquals("text", types.get("request_snapshot"));
        assertEquals("uuid", types.get("retry_of"));

        Integer interruptedAllowed = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'import'
                   AND t.relname = 'import_operations'
                   AND c.conname = 'chk_import_operations_status'
                   AND pg_get_constraintdef(c.oid) LIKE '%INTERRUPTED%'
                """,
                Integer.class
        );
        assertEquals(1, interruptedAllowed);
    }

    @Test
    void importOperationProjectStatisticsColumnsExist() {
        Map<String, String> types = jdbcTemplate.query(
                """
                SELECT column_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'import'
                   AND table_name = 'import_operations'
                   AND column_name IN (
                       'projects_total',
                       'projects_imported',
                       'projects_partial',
                       'projects_failed',
                       'projects_duplicate',
                       'projects_skipped'
                   )
                """,
                rs -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("column_name"), rs.getString("data_type"));
                    }
                    return result;
                }
        );

        assertEquals(6, types.size());
        types.values().forEach(type -> assertEquals("integer", type));

        Integer checkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'import'
                   AND t.relname = 'import_operations'
                   AND c.conname = 'chk_import_operation_project_counters_nonnegative'
                """,
                Integer.class
        );
        assertEquals(1, checkCount);
    }

    @Test
    void onlyOneStartedImportOperationIsAllowedPerPackage() {
        Map<String, Object> index = jdbcTemplate.queryForMap(
                """
                SELECT i.indisunique AS is_unique,
                       pg_get_expr(i.indpred, i.indrelid) AS predicate
                  FROM pg_index i
                  JOIN pg_class idx ON idx.oid = i.indexrelid
                  JOIN pg_class tbl ON tbl.oid = i.indrelid
                  JOIN pg_namespace n ON n.oid = tbl.relnamespace
                 WHERE n.nspname = 'import'
                   AND tbl.relname = 'import_operations'
                   AND idx.relname = 'uk_import_operations_one_started_per_package'
                """
        );

        assertEquals(Boolean.TRUE, index.get("is_unique"));
        String predicate = String.valueOf(index.get("predicate"));
        assertTrue(predicate.contains("STARTED"));
    }

    @Test
    void onlyOneMigrationBatchCanBeRunning() {
        Map<String, Object> index = jdbcTemplate.queryForMap(
                """
                SELECT i.indisunique AS is_unique,
                       pg_get_expr(i.indpred, i.indrelid) AS predicate
                  FROM pg_index i
                  JOIN pg_class idx ON idx.oid = i.indexrelid
                  JOIN pg_class tbl ON tbl.oid = i.indrelid
                  JOIN pg_namespace n ON n.oid = tbl.relnamespace
                 WHERE n.nspname = 'import'
                   AND tbl.relname = 'migration_batches'
                   AND idx.relname = 'uk_migration_batches_one_running'
                """
        );

        assertEquals(Boolean.TRUE, index.get("is_unique"));
        String predicate = String.valueOf(index.get("predicate"));
        assertTrue(predicate.contains("RUNNING"));
    }


    /**
     * The Java side stopped accepting an arbitrary source type when the enum landed, so the column
     * needs the same guard. Without it a row written by a script, a restore, or an older build
     * would load the entity and fail the whole request list with an unmappable value.
     */
    @Test
    void migrationSourceTypeIsConstrainedToTheEnumValues() {
        Integer constraintCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'import'
                   AND t.relname = 'migration_sources'
                   AND c.conname = 'ck_migration_sources_source_type'
                   AND c.contype = 'c'
                """,
                Integer.class
        );
        assertEquals(1, constraintCount);

        // Null stays allowed: a source that defers to legacy.datasource.* never needed a type, and
        // MigrationRunner only stamps one on the source it creates itself.
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO import.migration_sources (name, office_name, source_type)
                VALUES ('Unsupported type', ?, 'ORACLE')
                """, "schema-test-" + java.util.UUID.randomUUID()));

        Integer stored = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM import.migration_sources
                 WHERE source_type = 'ORACLE'
                """,
                Integer.class
        );
        assertEquals(0, stored);
    }

    @Test
    void branchPackageSourceOrganizationRemainsAnExternalIdentifier() {
        Integer sourceFkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                  JOIN pg_attribute a
                    ON a.attrelid = t.oid
                   AND a.attnum = ANY(c.conkey)
                 WHERE n.nspname = 'import'
                   AND t.relname = 'branch_packages'
                   AND c.contype = 'f'
                   AND a.attname = 'source_organization_id'
                """,
                Integer.class
        );
        assertEquals(0, sourceFkCount);

        Integer targetFkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                  JOIN pg_attribute a
                    ON a.attrelid = t.oid
                   AND a.attnum = ANY(c.conkey)
                 WHERE n.nspname = 'import'
                   AND t.relname = 'branch_packages'
                   AND c.contype = 'f'
                   AND a.attname = 'target_organization_id'
                """,
                Integer.class
        );
        assertEquals(1, targetFkCount);
    }

    @Test
    void auditLogImmutabilityTriggerExists() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_trigger tg
                  JOIN pg_class t ON t.oid = tg.tgrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'audit'
                   AND t.relname = 'audit_logs'
                   AND tg.tgname = 'trg_audit_logs_immutable'
                   AND NOT tg.tgisinternal
                """,
                Integer.class
        );

        assertEquals(1, count);
    }

    @Test
    void auditIpAddressPersistsThroughJpaIntoInetColumn() {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction("INTEGRATION_TEST");
        auditLog.setEntityType("AuditLog");
        auditLog.setDescription("PostgreSQL inet/jsonb mapping verification");
        auditLog.setIpAddress("127.0.0.1");
        auditLog.setOldValues("{\"status\":\"OLD\"}");
        auditLog.setNewValues("{\"status\":\"NEW\"}");

        AuditLog saved = auditLogRepository.saveAndFlush(auditLog);

        var stored = jdbcTemplate.queryForMap(
                """
                SELECT host(ip_address) AS ip,
                       old_values ->> 'status' AS old_status,
                       new_values ->> 'status' AS new_status
                  FROM audit.audit_logs
                 WHERE id = ?
                """,
                saved.getId()
        );

        assertEquals("127.0.0.1", stored.get("ip"));
        assertEquals("OLD", stored.get("old_status"));
        assertEquals("NEW", stored.get("new_status"));
    }

    @Test
    void projectVersionUniquenessConstraintExists() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'project'
                   AND t.relname = 'projects'
                   AND c.conname = 'uk_projects_project_uid_version'
                   AND c.contype = 'u'
                """,
                Integer.class
        );

        assertEquals(1, count);
    }
}
