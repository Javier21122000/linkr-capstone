package com.linkr.backend.ticketmaster;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementazione JDBC di {@link ExistingEventsSource}: contiene SOLO istruzioni SELECT e gira in una transazione di sola lettura,
 * quindi il database stesso rifiuterebbe qualunque scrittura (PostgreSQL: "cannot execute ... in a read-only transaction").
 */
@Component
public class JdbcExistingEventsSource implements ExistingEventsSource {

	/** Protezione: un intervallo di 30 giorni a Milano contiene poche centinaia di eventi LINKR, mai migliaia. */
	static final int MAX_EVENTS = 5000;

	/** Postgres accetta fino a ~32k parametri per istruzione: si interroga a blocchi. */
	private static final int ID_CHUNK = 500;

	private final JdbcClient jdbc;

	JdbcExistingEventsSource(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<String> importedExternalIds(Collection<String> externalIds) {
		Set<String> imported = new HashSet<>();
		List<String> ids = List.copyOf(externalIds);
		for (int from = 0; from < ids.size(); from += ID_CHUNK) {
			List<String> chunk = ids.subList(from, Math.min(from + ID_CHUNK, ids.size()));
			imported.addAll(jdbc.sql("SELECT external_id FROM event_source WHERE source = 'TICKETMASTER' AND external_id IN (:ids)")
					.param("ids", chunk)
					.query(String.class)
					.list());
		}
		return imported;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExistingEvent> eventsStartingBetween(Instant from, Instant to) {
		return jdbc.sql("""
				SELECT id, title, latitude, longitude, starts_at
				  FROM event
				 WHERE status <> 'DRAFT' AND starts_at BETWEEN :from AND :to
				 ORDER BY starts_at, id
				 LIMIT :limit
				""")
				.param("from", from.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("to", to.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("limit", MAX_EVENTS)
				.query((rs, rowNum) -> new ExistingEvent(
						rs.getObject("id", UUID.class),
						rs.getString("title"),
						rs.getDouble("latitude"),
						rs.getDouble("longitude"),
						rs.getObject("starts_at", OffsetDateTime.class).toInstant()))
				.list();
	}

}
