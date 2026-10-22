package com.linkr.backend.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.IntegrationTest;

/**
 * La tabella event_source creata da V9, sul database di test (linkr_test_db). Ogni test e' @Transactional (rollback finale);
 * un test per violazione, perche' in PostgreSQL un errore di vincolo interrompe la transazione.
 */
@IntegrationTest
@Transactional
class EventSourceMigrationTest {

	@Autowired
	private JdbcClient jdbc;

	private UUID insertEvent() {
		return jdbc.sql("""
				INSERT INTO event (title, description, category, starts_at, venue_name, address, neighbourhood, latitude, longitude)
				VALUES ('V9 test event', 'Created by a test', 'MUSIC', now() + interval '2 days', 'Venue', 'Address', 'Test', 45.46, 9.19)
				RETURNING id
				""").query(UUID.class).single();
	}

	private void insertSource(UUID eventId, String source, String externalId) {
		jdbc.sql("INSERT INTO event_source (event_id, source, external_id) VALUES (:e, :s, :x)")
				.param("e", eventId).param("s", source).param("x", externalId).update();
	}

	@Test
	void migrationV9HasBeenAppliedSuccessfullyOnTheTestDatabase() {
		assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("linkr_test_db");
		Map<String, Object> row = jdbc.sql("SELECT description, success FROM flyway_schema_history WHERE version = '9'").query().singleRow();

		assertThat(row.get("description")).isEqualTo("create event source");
		assertThat(row.get("success")).isEqualTo(true);
		assertThat(jdbc.sql("SELECT max(version::int) FROM flyway_schema_history WHERE success").query(Integer.class).single()).isGreaterThanOrEqualTo(9);
	}

	@Test
	void theTableHasTheDocumentedColumns() {
		List<Map<String, Object>> columns = jdbc.sql("""
				SELECT column_name, data_type, is_nullable FROM information_schema.columns
				 WHERE table_schema = current_schema() AND table_name = 'event_source' ORDER BY column_name
				""").query().listOfRows();

		assertThat(columns).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder("id", "event_id", "source", "external_id",
				"source_url", "source_status", "last_seen_at", "created_at", "updated_at");
		Map<String, String> nullable = new java.util.HashMap<>();
		columns.forEach(c -> nullable.put((String) c.get("column_name"), (String) c.get("is_nullable")));
		assertThat(nullable).containsEntry("event_id", "NO").containsEntry("source", "NO").containsEntry("external_id", "NO")
				.containsEntry("source_url", "YES").containsEntry("source_status", "YES").containsEntry("last_seen_at", "YES")
				.containsEntry("created_at", "NO").containsEntry("updated_at", "NO");
	}

	@Test
	void aLinkIsStoredWithDefaults() {
		UUID eventId = insertEvent();
		insertSource(eventId, "TICKETMASTER", "Za5ju3rKuqZDdO7Kb7vP");

		Map<String, Object> row = jdbc.sql("SELECT id, created_at, updated_at, last_seen_at, source_url FROM event_source WHERE event_id = :e")
				.param("e", eventId).query().singleRow();
		assertThat(row.get("id")).isNotNull();
		assertThat(row.get("created_at")).isNotNull();
		assertThat(row.get("updated_at")).isNotNull();
		assertThat(row.get("last_seen_at")).isNull();
		assertThat(row.get("source_url")).isNull();
	}

	@Test
	void theSameExternalListingCannotBeLinkedTwice() {
		UUID first = insertEvent();
		UUID second = insertEvent();
		insertSource(first, "TICKETMASTER", "dup-id");

		assertThatThrownBy(() -> insertSource(second, "TICKETMASTER", "dup-id")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("event_source_source_external_id_key");
	}

	@Test
	void anEventHasAtMostOneExternalOrigin() {
		UUID eventId = insertEvent();
		insertSource(eventId, "TICKETMASTER", "first-listing");

		// relazione 1:1: un secondo annuncio sullo stesso evento e' rifiutato anche se l'id esterno e' diverso
		assertThatThrownBy(() -> insertSource(eventId, "TICKETMASTER", "second-listing")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("event_source_event_id_key");
	}

	@Test
	void repeatingTheSameLinkOnTheSameEventIsRejectedToo() {
		UUID eventId = insertEvent();
		insertSource(eventId, "TICKETMASTER", "same-id");

		assertThatThrownBy(() -> insertSource(eventId, "TICKETMASTER", "same-id")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void differentEventsWithDifferentListingsAreFine() {
		insertSource(insertEvent(), "TICKETMASTER", "listing-a");
		insertSource(insertEvent(), "TICKETMASTER", "listing-b");

		assertThat(jdbc.sql("SELECT count(*) FROM event_source WHERE external_id IN ('listing-a', 'listing-b')").query(Long.class).single())
				.isEqualTo(2L);
	}

	@Test
	void onlyKnownSourcesAreAccepted() {
		UUID eventId = insertEvent();

		assertThatThrownBy(() -> insertSource(eventId, "EVENTBRITE", "x1")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("event_source_source_check");
	}

	@Test
	void aBlankExternalIdIsRejected() {
		UUID eventId = insertEvent();

		assertThatThrownBy(() -> insertSource(eventId, "TICKETMASTER", "   ")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("event_source_external_id_not_blank");
	}

	@Test
	void aLinkMustPointToAnExistingEvent() {
		assertThatThrownBy(() -> insertSource(UUID.randomUUID(), "TICKETMASTER", "orphan")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingTheEventRemovesItsLinks() {
		UUID eventId = insertEvent();
		insertSource(eventId, "TICKETMASTER", "will-go");

		jdbc.sql("DELETE FROM event WHERE id = :e").param("e", eventId).update();

		assertThat(jdbc.sql("SELECT count(*) FROM event_source WHERE external_id = 'will-go'").query(Long.class).single()).isZero();
	}

	@Test
	void theUniqueConstraintsOnEventIdAndOnSourceAndExternalIdCoverAllLookups() {
		List<String> indexes = jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'event_source'")
				.query(String.class).list();

		// niente indice separato su event_id: lo copre il vincolo UNIQUE (event_id)
		assertThat(indexes).containsExactlyInAnyOrder("event_source_pkey", "event_source_source_external_id_key", "event_source_event_id_key");
	}

}
