package com.centralArchiveSystem.Archive.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
    private static final Set<String> PRE_FLYWAY_RELATIONS = Set.of(
            "core.organizations",
            "core.users",
            "project.projects",
            "import.branch_packages",
            "import.package_projects"
    );

    @Test
    void baselinesRecognizedPreFlywayDatabaseBeforeMigrating() throws Exception {
        FlywayFixture fixture = flywayFixture(PRE_FLYWAY_RELATIONS, false);

        new FlywayMigrationConfig().migrate(fixture.flyway());

        var ordered = inOrder(fixture.flyway());
        ordered.verify(fixture.flyway()).baseline();
        ordered.verify(fixture.flyway()).migrate();
    }

    @Test
    void dropsEmptyHistoryTableThenBaselinesRecognizedPreFlywayDatabase() throws Exception {
        Set<String> relations = new java.util.HashSet<>(PRE_FLYWAY_RELATIONS);
        relations.add(HISTORY);

        FlywayFixture fixture = flywayFixture(relations, false);

        new FlywayMigrationConfig().migrate(fixture.flyway());

        verify(fixture.statement()).execute(
                "DROP TABLE \"public\".\"flyway_schema_history\""
        );
        var ordered = inOrder(fixture.flyway());
        ordered.verify(fixture.flyway()).baseline();
        ordered.verify(fixture.flyway()).migrate();
    }

    @Test
    void doesNotBaselineDatabaseThatAlreadyHasFlywayHistoryRows() throws Exception {
        Set<String> relations = new java.util.HashSet<>(PRE_FLYWAY_RELATIONS);
        relations.add(HISTORY);

        FlywayFixture fixture = flywayFixture(relations, true);

        new FlywayMigrationConfig().migrate(fixture.flyway());

        verify(fixture.statement(), never()).execute(anyString());
        verify(fixture.flyway(), never()).baseline();
        verify(fixture.flyway()).migrate();
    }

    @Test
    void doesNotBaselineFreshOrUnknownDatabase() throws Exception {
        FlywayFixture fixture = flywayFixture(Set.of(), false);

        new FlywayMigrationConfig().migrate(fixture.flyway());

        verify(fixture.flyway(), never()).baseline();
        verify(fixture.flyway()).migrate();
    }

    @Test
    void doesNotBaselinePartialLegacySchema() throws Exception {
        FlywayFixture fixture = flywayFixture(
                Set.of("core.organizations", "project.projects"),
                false
        );

        new FlywayMigrationConfig().migrate(fixture.flyway());

        verify(fixture.flyway(), never()).baseline();
        verify(fixture.flyway()).migrate();
    }

    private FlywayFixture flywayFixture(Set<String> relations, boolean historyHasRows)
            throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);

        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(configuration.getDefaultSchema()).thenReturn("public");
        when(configuration.getTable()).thenReturn("flyway_schema_history");
        when(dataSource.getConnection()).thenReturn(connection);

        when(connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL"))
                .thenAnswer(invocation -> statementForRelations(relations));

        when(connection.createStatement()).thenReturn(statement);

        ResultSet historyRowsResult = mock(ResultSet.class);
        when(historyRowsResult.next()).thenReturn(true);
        when(historyRowsResult.getBoolean(1)).thenReturn(historyHasRows);
        when(statement.executeQuery(
                "SELECT EXISTS (SELECT 1 FROM \"public\".\"flyway_schema_history\")"
        )).thenReturn(historyRowsResult);

        return new FlywayFixture(flyway, statement);
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

    private record FlywayFixture(Flyway flyway, Statement statement) {
    }
}
