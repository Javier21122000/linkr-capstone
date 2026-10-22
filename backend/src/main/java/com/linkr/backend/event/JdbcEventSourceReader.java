package com.linkr.backend.event;

import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.linkr.backend.event.dto.EventSourceResponse;

/** Legge event_source con una SELECT per blocco di id (una sola query per una lista normale). Solo lettura. */
@Component
public class JdbcEventSourceReader implements EventSourceReader {

	/** Postgres accetta ~32k parametri per istruzione: si interroga a blocchi. */
	private static final int CHUNK = 500;

	private final JdbcClient jdbc;

	public JdbcEventSourceReader(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, EventSourceResponse> findByEventIds(Collection<UUID> eventIds) {
		Map<UUID, EventSourceResponse> result = new HashMap<>();
		List<UUID> ids = List.copyOf(eventIds);
		for (int from = 0; from < ids.size(); from += CHUNK) {
			List<UUID> chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
			jdbc.sql("SELECT event_id, source, source_url FROM event_source WHERE event_id IN (:ids)")
					.param("ids", chunk)
					.query((rs, rowNum) -> {
						// Difesa in profondita': al browser arriva solo un link https (l'importazione ne scrive gia' solo di questo tipo).
						String url = rs.getString("source_url");
						result.put(rs.getObject("event_id", UUID.class), new EventSourceResponse(rs.getString("source"),
								url != null && url.startsWith("https://") ? url : null));
						return null;
					}).list();
		}
		return result;
	}

}
