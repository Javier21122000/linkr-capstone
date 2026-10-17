package com.linkr.backend.people;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.linkr.backend.participation.ParticipationStatus;
import com.linkr.backend.people.dto.SharedInterestResponse;

/**
 * Query SQL della discovery persone (JdbcClient: servono aggregazione e intersezione di interessi).
 * Per ogni pagina si eseguono SEMPRE 3 query, qualunque sia il numero di persone: nessun N+1.
 *
 * Filtro comune: stesso evento, visible_to_others = true, utente diverso da "me".
 * Tutti i valori arrivano come parametri (:nome), mai concatenati nell'SQL.
 */
@Repository
public class PeopleRepository {

	/** Riga di una persona: solo i campi che possono uscire dalle API. */
	record PersonRow(UUID userId, String username, String displayName, String avatarUrl, ParticipationStatus status) {
	}

	private final JdbcClient jdbc;

	public PeopleRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Query 1: quante persone visibili ci sono. */
	long countVisible(UUID eventId, UUID me) {
		return jdbc.sql("""
				SELECT count(*) FROM event_participation p
				WHERE p.event_id = :eventId AND p.visible_to_others AND p.user_id <> :me
				""")
				.param("eventId", eventId).param("me", me)
				.query(Long.class).single();
	}

	/**
	 * Query 2: una pagina di persone, con il numero di interessi in comune (sotto-query correlata che usa la PK
	 * di user_interest) usato per l'ordinamento.
	 */
	List<PersonRow> findPage(UUID eventId, UUID me, PeopleSort sort, int size, long offset) {
		return jdbc.sql("""
				SELECT u.id, u.username, u.display_name, u.avatar_url, p.status,
				       (SELECT count(*)
				          FROM user_interest theirs
				          JOIN user_interest mine ON mine.interest_id = theirs.interest_id AND mine.user_id = :me
				         WHERE theirs.user_id = u.id) AS shared_count
				  FROM event_participation p
				  JOIN app_user u ON u.id = p.user_id
				 WHERE p.event_id = :eventId AND p.visible_to_others AND p.user_id <> :me
				 ORDER BY\s""" + sort.orderBy() + """
				 LIMIT :size OFFSET :offset
				""")
				.param("eventId", eventId).param("me", me).param("size", size).param("offset", offset)
				.query((rs, rowNum) -> new PersonRow(
						rs.getObject("id", UUID.class),
						rs.getString("username"),
						rs.getString("display_name"),
						rs.getString("avatar_url"),
						ParticipationStatus.valueOf(rs.getString("status"))))
				.list();
	}

	/**
	 * Query 3: gli interessi in comune di TUTTE le persone della pagina insieme, nell'ordine del catalogo.
	 * Il join con gli interessi di "me" garantisce che esca solo l'intersezione.
	 */
	Map<UUID, List<SharedInterestResponse>> findSharedInterests(UUID me, List<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		record Row(UUID userId, SharedInterestResponse interest) {
		}
		return jdbc.sql("""
				SELECT theirs.user_id, i.id, i.slug, i.name, c.slug AS category_slug
				  FROM user_interest theirs
				  JOIN user_interest mine ON mine.interest_id = theirs.interest_id AND mine.user_id = :me
				  JOIN interest i ON i.id = theirs.interest_id
				  JOIN interest_category c ON c.id = i.category_id
				 WHERE theirs.user_id IN (:userIds)
				 ORDER BY c.sort_order, i.sort_order
				""")
				.param("me", me).param("userIds", userIds)
				.query((rs, rowNum) -> new Row(rs.getObject("user_id", UUID.class),
						new SharedInterestResponse(rs.getObject("id", UUID.class), rs.getString("slug"),
								rs.getString("name"), rs.getString("category_slug"))))
				.list().stream()
				.collect(Collectors.groupingBy(Row::userId, Collectors.mapping(Row::interest, Collectors.toList())));
	}

}
