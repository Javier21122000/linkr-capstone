package com.linkr.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Garantisce che i test di integrazione lavorino sul database dedicato e non su linkr_db. */
@IntegrationTest
class TestDatabaseIsolationTest {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void integrationTestsRunOnTheDedicatedTestDatabase() {
		String database = jdbc.sql("SELECT current_database()").query(String.class).single();

		assertThat(database).isEqualTo("linkr_test_db");
	}

}
