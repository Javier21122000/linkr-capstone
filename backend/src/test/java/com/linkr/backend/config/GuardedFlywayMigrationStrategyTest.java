package com.linkr.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Test della protezione con Flyway e database finti: nessuna connessione reale, nessun rischio per i dati. */
class GuardedFlywayMigrationStrategyTest {

	private final Flyway flyway = mock(Flyway.class);
	private final Configuration configuration = mock(Configuration.class);
	private final DataSource dataSource = mock(DataSource.class);

	GuardedFlywayMigrationStrategyTest() {
		when(flyway.getConfiguration()).thenReturn(configuration);
		when(configuration.getDataSource()).thenReturn(dataSource);
	}

	@Test
	void checksTheDatabaseBeforeMigrating() throws SQLException {
		connectedTo("linkr_db");

		new GuardedFlywayMigrationStrategy("linkr_db").migrate(flyway);

		InOrder order = inOrder(dataSource, flyway);
		order.verify(dataSource).getConnection();
		order.verify(flyway).migrate();
	}

	@Test
	void wrongDatabaseStopsBeforeAnyMigration() throws SQLException {
		connectedTo("ChatConAi-18-SETTEMBRE");

		assertThatThrownBy(() -> new GuardedFlywayMigrationStrategy("linkr_db").migrate(flyway))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ChatConAi-18-SETTEMBRE")
				.hasMessageContaining("linkr_db");

		verify(flyway, never()).migrate();
	}

	@Test
	void unreachableDatabaseStopsBeforeAnyMigration() throws SQLException {
		when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

		assertThatThrownBy(() -> new GuardedFlywayMigrationStrategy("linkr_db").migrate(flyway))
				.isInstanceOf(IllegalStateException.class);

		verify(flyway, never()).migrate();
	}

	@Test
	void missingDataSourceStopsBeforeAnyMigration() {
		when(configuration.getDataSource()).thenReturn(null);

		assertThatThrownBy(() -> new GuardedFlywayMigrationStrategy("linkr_db").migrate(flyway))
				.isInstanceOf(IllegalStateException.class);

		verify(flyway, never()).migrate();
	}

	@Test
	void differentNameIsAllowedWhenConfiguredForDeployment() throws SQLException {
		connectedTo("linkr_production");

		new GuardedFlywayMigrationStrategy("linkr_production").migrate(flyway);

		verify(flyway).migrate();
	}

	@Test
	void emptyExpectedNameDisablesTheCheck() {
		new GuardedFlywayMigrationStrategy("").migrate(flyway);

		verifyNoInteractions(dataSource);
		verify(flyway).migrate();
	}

	private void connectedTo(String databaseName) throws SQLException {
		Connection connection = mock(Connection.class);
		Statement statement = mock(Statement.class);
		ResultSet resultSet = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.createStatement()).thenReturn(statement);
		when(statement.executeQuery(anyString())).thenReturn(resultSet);
		when(resultSet.next()).thenReturn(true);
		when(resultSet.getString(1)).thenReturn(databaseName);
	}

}
