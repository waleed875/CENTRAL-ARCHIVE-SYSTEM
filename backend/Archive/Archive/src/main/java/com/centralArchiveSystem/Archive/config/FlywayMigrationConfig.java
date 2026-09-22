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
import java.sql.Statement;

@Configuration
public class FlywayMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationConfig.class);
    private static final String FALLBACK_DEFAULT_SCHEMA = "public";

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy() {
        return this::migrate;
    }

    void migrate(Flyway flyway) {
        if (preparePreFlywayDatabaseForBaseline(flyway)) {
            log.warn(
                    "Detected a pre-Flyway CENTRAL-ARCHIVE-SYSTEM database. "
                            + "Recording baseline version 1 before applying compatibility migrations."
            );
            flyway.baseline();
        }

        flyway.migrate();
    }

    boolean preparePreFlywayDatabaseForBaseline(Flyway flyway) {
        var configuration = flyway.getConfiguration();
        DataSource dataSource = configuration.getDataSource();

        String defaultSchema = configuration.getDefaultSchema();
        if (defaultSchema == null || defaultSchema.isBlank()) {
            defaultSchema = FALLBACK_DEFAULT_SCHEMA;
        }

        String historyTable = configuration.getTable();
        String qualifiedHistoryTable = defaultSchema + "." + historyTable;

        try (Connection connection = dataSource.getConnection()) {
            boolean historyExists = relationExists(connection, qualifiedHistoryTable);

            if (historyExists && historyHasRows(connection, qualifiedHistoryTable)) {
                return false;
            }

            if (!looksLikePreFlywayArchiveDatabase(connection)) {
                return false;
            }

            if (historyExists) {
                log.warn(
                        "Removing empty Flyway schema history table left by an unsuccessful "
                                + "initial migration attempt before baselining the existing database."
                );
                dropEmptyHistoryTable(connection, defaultSchema, historyTable);
            }

            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to inspect the database before Flyway migration",
                    exception
            );
        }
    }

    private boolean looksLikePreFlywayArchiveDatabase(Connection connection) throws SQLException {
        return relationExists(connection, "core.organizations")
                && relationExists(connection, "core.users")
                && relationExists(connection, "project.projects")
                && relationExists(connection, "import.branch_packages")
                && relationExists(connection, "import.package_projects");
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

    private boolean historyHasRows(Connection connection, String qualifiedHistoryTable)
            throws SQLException {
        String safeQualifiedName = quoteQualifiedIdentifier(qualifiedHistoryTable);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT EXISTS (SELECT 1 FROM " + safeQualifiedName + ")"
             )) {
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private void dropEmptyHistoryTable(
            Connection connection,
            String schema,
            String table
    ) throws SQLException {
        String safeQualifiedName = quoteIdentifier(schema) + "." + quoteIdentifier(table);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + safeQualifiedName);
        }
    }

    private String quoteQualifiedIdentifier(String qualifiedName) {
        int dotIndex = qualifiedName.indexOf('.');
        if (dotIndex <= 0 || dotIndex == qualifiedName.length() - 1) {
            throw new IllegalArgumentException("Expected schema-qualified identifier");
        }

        return quoteIdentifier(qualifiedName.substring(0, dotIndex))
                + "."
                + quoteIdentifier(qualifiedName.substring(dotIndex + 1));
    }

    private String quoteIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return "\\\"" + identifier + "\\\"";
    }
}
