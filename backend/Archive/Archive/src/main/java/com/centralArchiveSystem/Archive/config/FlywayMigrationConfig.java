package com.centralArchiveSystem.Archive.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Configuration
public class FlywayMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationConfig.class);
    private static final String FALLBACK_DEFAULT_SCHEMA = "public";

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy() {
        return this::migrate;
    }

    void migrate(Flyway flyway) {
        if (shouldBaselineExistingDatabase(flyway)) {
            log.warn(
                    "Detected a pre-Flyway CENTRAL-ARCHIVE-SYSTEM database. "
                            + "Recording baseline version 1 before applying compatibility migrations."
            );
            flyway.baseline();
        }

        flyway.migrate();
    }

    boolean shouldBaselineExistingDatabase(Flyway flyway) {
        var configuration = flyway.getConfiguration();
        DataSource dataSource = configuration.getDataSource();

        String defaultSchema = configuration.getDefaultSchema();
        if (defaultSchema == null || defaultSchema.isBlank()) {
            defaultSchema = FALLBACK_DEFAULT_SCHEMA;
        }

        String historyTable = configuration.getTable();

        try (Connection connection = dataSource.getConnection()) {
            if (relationExists(connection, defaultSchema + "." + historyTable)) {
                return false;
            }

            return relationExists(connection, "core.organizations")
                    && relationExists(connection, "project.projects")
                    && relationExists(connection, "import.branch_packages")
                    && relationExists(connection, "audit.audit_logs");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to inspect the database before Flyway migration",
                    exception
            );
        }
    }

    private boolean relationExists(Connection connection, String qualifiedRelationName)
            throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, qualifiedRelationName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }
}
