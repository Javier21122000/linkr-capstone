package com.linkr.backend.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.linkr.backend.IntegrationTest;

/**
 * Prova che V9 e' STRETTAMENTE ADDITIVA su dati veri: si crea uno schema usa-e-getta DENTRO linkr_test_db (mai "public", mai linkr_db),
 * ci si porta alla versione 8, si inseriscono utenti, un evento demo, un evento della community e una partecipazione, si applica V9 e si
 * verifica che ogni riga, ogni colonna, ogni vincolo e ogni indice delle tabelle esistenti siano identici. Lo schema viene eliminato alla fine.
 *
 * Questa classe NON e' @Transactional: Flyway lavora con connessioni proprie. La pulizia e' nel @AfterEach.
 */
@IntegrationTest
class FlywayV9UpgradeTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcClient jdbc;

	private String schema;

	@BeforeEach
	void useAScratchSchemaOfTheTestDatabaseOnly() {
		assertThat(jdbc.sql("SELECT current_database()").query(String.class).single())
				.as("questo test puo' girare solo su linkr_test_db").isEqualTo("linkr_test_db");
		schema = "flyway_v9_check_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	@AfterEach
	void dropTheScratchSchema() {
		if (schema != null) {
			jdbc.sql("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE").update();
		}
	}

	private Flyway flywayUpTo(String version) {
		return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
				.locations("classpath:db/migration").target(version).load();
	}

	private String q(String table) {
		return "\"" + schema + "\"." + table;
	}

	@Test
	void applyingV9ToAPopulatedV8DatabaseChangesNothingThatAlreadyExisted() {
		flywayUpTo("8").migrate();
		assertThat(jdbc.sql("SELECT to_regclass(:name) IS NULL").param("name", q("event_source")).query(Boolean.class).single())
				.as("alla versione 8 event_source non esiste ancora").isTrue();

		UUID admin = jdbc.sql("INSERT INTO " + q("app_user") + " (username, email, password_hash, display_name, role) "
				+ "VALUES ('v9admin', 'v9admin@example.com', 'x', 'V9 Admin', 'ADMIN') RETURNING id").query(UUID.class).single();
		UUID fan = jdbc.sql("INSERT INTO " + q("app_user") + " (username, email, password_hash, display_name) "
				+ "VALUES ('v9fan', 'v9fan@example.com', 'x', 'V9 Fan') RETURNING id").query(UUID.class).single();
		UUID demo = jdbc.sql("INSERT INTO " + q("event") + " (title, description, category, starts_at, venue_name, address, neighbourhood, "
				+ "latitude, longitude, demo, event_type) VALUES ('Evento demo', 'Demo', 'MUSIC', now() + interval '3 days', 'Sede', 'Via', "
				+ "'Isola', 45.48, 9.19, true, 'OFFICIAL') RETURNING id").query(UUID.class).single();
		UUID community = jdbc.sql("INSERT INTO " + q("event") + " (title, description, category, starts_at, venue_name, address, neighbourhood, "
				+ "latitude, longitude, demo, event_type, created_by) VALUES ('Evento community', 'Creato da un utente', 'FOOD', "
				+ "now() + interval '4 days', 'Sede', 'Via', 'Navigli', 45.45, 9.17, false, 'COMMUNITY', :fan) RETURNING id")
				.param("fan", fan).query(UUID.class).single();
		jdbc.sql("INSERT INTO " + q("event_participation") + " (user_id, event_id, status, visible_to_others) VALUES (:u, :e, 'GOING', true)")
				.param("u", admin).param("e", community).update();
		jdbc.sql("INSERT INTO " + q("event_participation") + " (user_id, event_id, status) VALUES (:u, :e, 'INTERESTED')")
				.param("u", fan).param("e", demo).update();

		Map<String, String> rowsBefore = rows();
		Map<String, String> structureBefore = structure();
		long tablesBefore = tableCount();
		assertThat(rowsBefore.get("event")).startsWith("2:");
		assertThat(rowsBefore.get("event_participation")).startsWith("2:");
		assertThat(rowsBefore.get("app_user")).startsWith("2:");

		flywayUpTo("9").migrate();

		// 1) le tabelle di prima: stesse righe, byte per byte
		assertThat(rows()).isEqualTo(rowsBefore);
		// 2) le tabelle di prima: stessi tipi di colonna, vincoli (con definizione) e indici
		assertThat(structure()).isEqualTo(structureBefore);
		// 3) l'unica novita' e' la tabella nuova, vuota
		assertThat(tableCount()).isEqualTo(tablesBefore + 1);
		assertThat(jdbc.sql("SELECT count(*) FROM " + q("event_source")).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT success FROM " + q("flyway_schema_history") + " WHERE version = '9'").query(Boolean.class).single()).isTrue();
		// 4) le specifiche di prodotto sulle righe: demo, community e partecipazioni identificabili come prima
		assertThat(jdbc.sql("SELECT demo FROM " + q("event") + " WHERE id = :id").param("id", demo).query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("SELECT event_type || ':' || created_by FROM " + q("event") + " WHERE id = :id").param("id", community)
				.query(String.class).single()).isEqualTo("COMMUNITY:" + fan);
	}

	@Test
	void applyingV9OnAnEmptyV8DatabaseAlsoWorksAndIsRepeatable() {
		flywayUpTo("8").migrate();

		assertThat(flywayUpTo("9").migrate().migrationsExecuted).as("da V8 a V9 si esegue la sola V9").isEqualTo(1);
		assertThat(flywayUpTo("9").migrate().migrationsExecuted).as("rilanciare la migrazione non fa nulla").isZero();

		MigrationInfoService info = flywayUpTo("9").info();
		MigrationInfo[] applied = info.applied();

		// applied() contiene 10 elementi, non 9: quando e' Flyway a creare lo schema (qui lo schema usa-e-getta) scrive nella cronologia anche
		// una riga SINTETICA "<< Flyway Schema Creation >>" di tipo SCHEMA. Non e' una migrazione: la si riconosce e la si esclude esplicitamente.
		List<MigrationInfo> synthetic = Arrays.stream(applied).filter(m -> m.getType().isSynthetic()).toList();
		assertThat(synthetic).singleElement().satisfies(row -> {
			assertThat(row.getType()).isEqualTo(CoreMigrationType.SCHEMA);
			assertThat(row.getDescription()).contains("Schema Creation");
		});

		// Le migrazioni vere: esattamente V1..V9, ognuna una sola volta (V9 compresa), tutte SQL e riuscite.
		List<MigrationInfo> migrations = Arrays.stream(applied).filter(m -> !m.getType().isSynthetic()).toList();
		assertThat(migrations).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
		assertThat(migrations).allSatisfy(m -> {
			assertThat(m.getType()).isEqualTo(CoreMigrationType.SQL);
			assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS);
		});
		assertThat(applied).hasSize(migrations.size() + synthetic.size());

		// Nessuna migrazione fallita in nessuno stato, e nulla in attesa fino alla versione obiettivo.
		assertThat(info.all()).noneMatch(m -> m.getState().isFailed());
		assertThat(info.pending()).isEmpty();
	}

	// ---------- fotografie dello schema usa-e-getta ----------

	/** tabella -> "righe:impronta" per tutte le tabelle che esistono gia' alla versione 8. */
	private Map<String, String> rows() {
		Map<String, String> result = new LinkedHashMap<>();
		for (String table : new String[] { "event", "event_participation", "app_user", "interest_category", "interest", "user_interest",
				"link_request", "chat_message" }) {
			result.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM "
					+ q(table) + " t").query(String.class).single());
		}
		return result;
	}

	/** Impronta di colonne, vincoli e indici di tutte le tabelle ESCLUSA event_source e la cronologia di Flyway. */
	private Map<String, String> structure() {
		Map<String, String> result = new LinkedHashMap<>();
		result.put("columns", jdbc.sql("""
				SELECT coalesce(md5(string_agg(table_name || '.' || column_name || ':' || data_type || ':' || is_nullable || ':'
				       || coalesce(column_default, ''), '|' ORDER BY table_name, column_name)), '')
				  FROM information_schema.columns
				 WHERE table_schema = :schema AND table_name NOT IN ('event_source', 'flyway_schema_history')
				""").param("schema", schema).query(String.class).single());
		result.put("constraints", jdbc.sql("""
				SELECT coalesce(md5(string_agg(c.conrelid::regclass::text || '.' || c.conname || ':' || pg_get_constraintdef(c.oid),
				       '|' ORDER BY c.conrelid::regclass::text, c.conname)), '')
				  FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
				 WHERE n.nspname = :schema AND c.conrelid::regclass::text NOT LIKE '%event_source%'
				   AND c.conrelid::regclass::text NOT LIKE '%flyway_schema_history%'
				""").param("schema", schema).query(String.class).single());
		result.put("indexes", jdbc.sql("""
				SELECT coalesce(md5(string_agg(tablename || '.' || indexname || ':' || indexdef, '|' ORDER BY tablename, indexname)), '')
				  FROM pg_indexes
				 WHERE schemaname = :schema AND tablename NOT IN ('event_source', 'flyway_schema_history')
				""").param("schema", schema).query(String.class).single());
		return result;
	}

	private long tableCount() {
		return jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = :schema AND table_type = 'BASE TABLE'")
				.param("schema", schema).query(Long.class).single();
	}

}
