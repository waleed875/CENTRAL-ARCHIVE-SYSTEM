package com.centralArchiveSystem.Archive;

import com.centralArchiveSystem.Archive.audit.AuditLog;
import com.centralArchiveSystem.Archive.audit.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
                 WHERE version = '14'
                   AND success = true
                """,
                Integer.class
        );
        assertEquals(1, latestMigration);
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
