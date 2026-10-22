package com.linkr.backend.ticketmaster;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.linkr.backend.event.EventCategory;

/**
 * Implementazione JDBC di {@link TicketmasterImportStore}. Le transazioni le apre il chiamante (un evento per transazione).
 *
 * Come si capisce che una persona ha modificato un evento importato, senza colonne in piu': l'importazione scrive sempre
 * event.updated_at ed event_source.updated_at con LO STESSO valore (syncAt). Una modifica fatta da una persona (o un annullamento)
 * aggiorna solo event.updated_at, che diventa successivo: da quel momento l'importazione non riscrive piu' il contenuto.
 * event_source.updated_at, percio', non va mai fatto avanzare se non quando si riscrive il contenuto dell'evento.
 */
@Component
class JdbcTicketmasterImportStore implements TicketmasterImportStore {

	private final JdbcClient jdbc;

	JdbcTicketmasterImportStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Linked> findLinked(String externalId) {
		return jdbc.sql("""
				SELECT e.id, e.title, e.description, e.category, e.status, e.event_type, e.demo, e.created_by,
				       e.starts_at, e.ends_at, e.venue_name, e.address, e.neighbourhood, e.latitude, e.longitude,
				       e.updated_at AS event_updated_at, s.updated_at AS source_updated_at
				  FROM event_source s JOIN event e ON e.id = s.event_id
				 WHERE s.source = 'TICKETMASTER' AND s.external_id = :externalId
				""")
				.param("externalId", externalId)
				.query((rs, rowNum) -> {
					OffsetDateTime endsAt = rs.getObject("ends_at", OffsetDateTime.class);
					ImportedContent content = new ImportedContent(
							rs.getString("title"),
							rs.getString("description"),
							EventCategory.valueOf(rs.getString("category")),
							rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
							endsAt == null ? null : endsAt.toInstant(),
							rs.getString("venue_name"),
							rs.getString("address"),
							rs.getString("neighbourhood"),
							rs.getDouble("latitude"),
							rs.getDouble("longitude"));
					return new Linked(
							rs.getObject("id", UUID.class),
							content,
							rs.getString("status"),
							rs.getString("event_type"),
							rs.getBoolean("demo"),
							rs.getObject("created_by", UUID.class),
							rs.getObject("event_updated_at", OffsetDateTime.class).toInstant(),
							rs.getObject("source_updated_at", OffsetDateTime.class).toInstant());
				})
				.optional();
	}

	@Override
	public UUID insertNew(ImportedContent c, String externalId, String sourceUrl, String sourceStatus, Instant syncAt) {
		UUID eventId = jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address, neighbourhood,
				                   latitude, longitude, image_url, image_alt, image_credit, demo, event_type, created_by,
				                   created_at, updated_at)
				VALUES (:title, :description, :category, 'PUBLISHED', :startsAt, :endsAt, :venueName, :address, :neighbourhood,
				        :latitude, :longitude, NULL, NULL, NULL, false, 'OFFICIAL', NULL, :syncAt, :syncAt)
				RETURNING id
				""")
				.param("title", c.title())
				.param("description", c.description())
				.param("category", c.category().name())
				.param("startsAt", at(c.startsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("endsAt", c.endsAt() == null ? null : at(c.endsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("venueName", c.venueName())
				.param("address", c.address())
				.param("neighbourhood", c.neighbourhood())
				.param("latitude", c.latitude())
				.param("longitude", c.longitude())
				.param("syncAt", at(syncAt), Types.TIMESTAMP_WITH_TIMEZONE)
				.query(UUID.class).single();

		jdbc.sql("""
				INSERT INTO event_source (event_id, source, external_id, source_url, source_status, last_seen_at, created_at, updated_at)
				VALUES (:eventId, 'TICKETMASTER', :externalId, :sourceUrl, :sourceStatus, :syncAt, :syncAt, :syncAt)
				""")
				.param("eventId", eventId)
				.param("externalId", externalId)
				.param("sourceUrl", sourceUrl)
				.param("sourceStatus", sourceStatus, Types.VARCHAR)
				.param("syncAt", at(syncAt), Types.TIMESTAMP_WITH_TIMEZONE)
				.update();
		return eventId;
	}

	@Override
	public boolean updateContent(Linked current, ImportedContent c, String sourceUrl, String sourceStatus, Instant syncAt) {
		int changed = jdbc.sql("""
				UPDATE event
				   SET title = :title, description = :description, category = :category, starts_at = :startsAt, ends_at = :endsAt,
				       venue_name = :venueName, address = :address, neighbourhood = :neighbourhood,
				       latitude = :latitude, longitude = :longitude, updated_at = :syncAt
				 WHERE id = :id AND updated_at = :expectedUpdatedAt
				   AND status = 'PUBLISHED' AND event_type = 'OFFICIAL' AND demo = false AND created_by IS NULL
				   AND EXISTS (SELECT 1 FROM event_source s WHERE s.event_id = event.id AND s.source = 'TICKETMASTER')
				""")
				.param("id", current.eventId())
				.param("expectedUpdatedAt", at(current.eventUpdatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("title", c.title())
				.param("description", c.description())
				.param("category", c.category().name())
				.param("startsAt", at(c.startsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("endsAt", c.endsAt() == null ? null : at(c.endsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("venueName", c.venueName())
				.param("address", c.address())
				.param("neighbourhood", c.neighbourhood())
				.param("latitude", c.latitude())
				.param("longitude", c.longitude())
				.param("syncAt", at(syncAt), Types.TIMESTAMP_WITH_TIMEZONE)
				.update();
		if (changed == 0) {
			return false;
		}
		int linked = jdbc.sql("""
				UPDATE event_source
				   SET source_url = :sourceUrl, source_status = :sourceStatus, last_seen_at = :syncAt, updated_at = :syncAt
				 WHERE event_id = :id AND source = 'TICKETMASTER'
				""")
				.param("id", current.eventId())
				.param("sourceUrl", sourceUrl)
				.param("sourceStatus", sourceStatus, Types.VARCHAR)
				.param("syncAt", at(syncAt), Types.TIMESTAMP_WITH_TIMEZONE)
				.update();
		if (linked != 1) {
			// Non dovrebbe accadere (il collegamento esiste: lo si e' appena letto). L'eccezione annulla anche l'UPDATE dell'evento.
			throw new IllegalStateException("The Ticketmaster link of the event disappeared during the update");
		}
		return true;
	}

	@Override
	public boolean recordSeen(String externalId, String sourceUrl, String sourceStatus, Instant seenAt) {
		return jdbc.sql("""
				UPDATE event_source
				   SET source_url = COALESCE(:sourceUrl, source_url), source_status = COALESCE(:sourceStatus, source_status),
				       last_seen_at = :seenAt
				 WHERE source = 'TICKETMASTER' AND external_id = :externalId
				""")
				.param("externalId", externalId)
				.param("sourceUrl", sourceUrl, Types.VARCHAR)
				.param("sourceStatus", sourceStatus, Types.VARCHAR)
				.param("seenAt", at(seenAt), Types.TIMESTAMP_WITH_TIMEZONE)
				.update() == 1;
	}

	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
