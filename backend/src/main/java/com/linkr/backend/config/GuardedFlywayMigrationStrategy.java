package com.linkr.backend.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Protezione contro i database sbagliati.
 *
 * Spring Boot esegue le migrazioni Flyway chiamando proprio questa strategia (e Hibernate parte solo dopo):
 * qui, PRIMA di flyway.migrate(), chiediamo al database a cui siamo davvero collegati come si chiama
 * (SELECT current_database()) e ci fermiamo se non e' quello atteso.
 *
 * Il nome atteso e' linkr.database.expected-name (default: linkr_db). Per un deploy con un database
 * dal nome diverso basta impostare LINKR_DB_EXPECTED_NAME; se e' vuoto il controllo e' disattivato.
 */
@Component
public class GuardedFlywayMigrationStrategy implements FlywayMigrationStrategy {

	private static final Logger log = LoggerFactory.getLogger(GuardedFlywayMigrationStrategy.class);

	private final String expectedDatabaseName;

	public GuardedFlywayMigrationStrategy(@Value("${linkr.database.expected-name:}") String expectedDatabaseName) {
		this.expectedDatabaseName = expectedDatabaseName;
	}

	@Override
	public void migrate(Flyway flyway) {
		verifyDatabase(flyway.getConfiguration().getDataSource());
		flyway.migrate();
	}

	void verifyDatabase(DataSource dataSource) {
		if (!StringUtils.hasText(expectedDatabaseName)) {
			log.warn("Database name check disabled (linkr.database.expected-name is empty)");
			return;
		}
		if (dataSource == null) {
			throw new IllegalStateException("Cannot verify the target database: Flyway has no DataSource");
		}

		String actual = currentDatabase(dataSource);
		if (!expectedDatabaseName.equals(actual)) {
			// Il messaggio non contiene l'URL: potrebbe includere credenziali.
			throw new IllegalStateException("Refusing to run Flyway migrations: connected to database '" + actual
					+ "' but this application expects '" + expectedDatabaseName + "'. "
					+ "Check LINKR_DB_URL. If this database name is intentional (e.g. production), "
					+ "set LINKR_DB_EXPECTED_NAME.");
		}
		log.info("Database check passed: connected to '{}'", actual);
	}

	private String currentDatabase(DataSource dataSource) {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT current_database()")) {
			result.next();
			return result.getString(1);
		}
		catch (SQLException e) {
			throw new IllegalStateException("Cannot verify the target database before running Flyway migrations", e);
		}
	}

}
