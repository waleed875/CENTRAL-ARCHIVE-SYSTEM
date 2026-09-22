package com.centralArchiveSystem.Archive.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlywayMigrationConfigTest {

    private static final String HISTORY = "public.flyway_schema_history";

    @Test
    void baselinesRecognizedPreFlywayDatabaseBeforeMigrating() throws Exception {
        Flyway flyway = flywayWithRelations(Set.of(
                "core.organizations",
                "project.projects",
                "import.branch_packages",
                "audit.audit_logs"
        ));

        new FlywayMigrationConfig().migrate(flyway);

        var ordered = inOrder(flyway);
        ordered.verify(flyway).baseline();
        ordered.verify(flyway).migrate();
    }

    @Test
    void doesNotBaselineDatabaseThatAlreadyHasFlywayHistory() throws Exception {
        Flyway flyway = flywayWithRelations(Set.of(
                HISTORY,
                "core.organizations",
                "project.projects",
                "import.branch_packages",
                "audit.audit_logs"
        ));

        new FlywayMigrationConfig().migrate(flyway);

        verify(flyway, never()).baseline();
        verify(flyway).migrate();
    }

    @Test
    void doesNotBaselineFreshOrUnknownDatabase() throws Exception {
        Flyway flyway = flywayWithRelations(Set.of());

        new FlywayMigrationConfig().migrate(flyway);

        verify(flyway, never()).baseline();
        verify(flyway).migrate();
    }

    private Flyway flywayWithRelations(Set<String> relations) throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);

        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(configuration.getDefaultSchema()).thenReturn("public");
        when(configuration.getTable()).thenReturn("flyway_schema_history");
        when(dataSource.getConnection()).thenReturn(connection);

        when(connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL"))
                .thenAnswer(invocation -> statementForRelations(relations));

        return flyway;
    }

    private PreparedStatement statementForRelations(Set<String> relations) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        AtomicReference<String> relation = new AtomicReference<>();

        doAnswer(invocation -> {
            relation.set(invocation.getArgument(1, String.class));
            return null;
        }).when(statement).setString(eq(1), anyString());

        when(statement.executeQuery()).thenAnswer(invocation -> {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getBoolean(1)).thenReturn(relations.contains(relation.get()));
            return resultSet;
        });

        return statement;
    }
}
