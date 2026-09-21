package com.centralArchiveSystem.Archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PostgresSchemaIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void runsAgainstPostgresqlAndAppliesLatestFlywayMigration() {
        String version = jdbcTemplate.queryForObject(
                "SELECT version()",
                String.class
        );
        assertNotNull(version);
        assertTrue(version.startsWith("PostgreSQL"));

        Integer v7 = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM flyway_schema_history
                 WHERE version = '7'
                   AND success = true
                """,
                Integer.class
        );
        assertEquals(1, v7);
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
