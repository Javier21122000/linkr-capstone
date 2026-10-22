package com.linkr.backend.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.linkr.backend.people.dto.SharedInterestResponse;

/** SQL del profilo pubblico (JdbcClient). Tutti i valori sono parametri (:nome). */
@Repository
class ProfileRepository {

	record ProfileRow(UUID userId, String username, String displayName, String bio, String avatarUrl) {
	}

	private final JdbcClient jdbc;

	ProfileRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Il profilo di "target" SE "viewer" ha diritto di vederlo; vuoto altrimenti (utente inesistente o non visibile: indistinguibili).
	 * Si vede un profilo se vale almeno una di queste regole (le stesse del resto di LINKR):
	 *  1. Discover People: la persona e' visibile (opt-in) e lo sono anch'io (reciprocita');
	 *  2. evento: partecipo a un evento pubblicato e non terminato in cui la persona e' visibile agli altri
	 *     (fine = ends_at oppure starts_at + 3 ore, come EventService.hasEnded);
	 *  3. Link: tra noi c'e' una richiesta accettata.
	 * Chi ha disattivato la visibilita' ed e' privato in ogni evento resta quindi invisibile a chi non ha un Link.
	 */
	Optional<ProfileRow> findVisible(UUID viewer, UUID target) {
		return jdbc.sql("""
				SELECT u.id, u.username, u.display_name, u.bio, u.avatar_url
				  FROM app_user u
				 WHERE u.id = :target
				   AND (
				        (u.discoverable AND EXISTS (SELECT 1 FROM app_user v WHERE v.id = :viewer AND v.discoverable))
				     OR EXISTS (
				            SELECT 1
				              FROM event_participation theirs
				              JOIN event_participation mine ON mine.event_id = theirs.event_id AND mine.user_id = :viewer
				              JOIN event e ON e.id = theirs.event_id
				             WHERE theirs.user_id = :target AND theirs.visible_to_others
				               AND e.status = 'PUBLISHED'
				               AND now() < COALESCE(e.ends_at, e.starts_at + interval '3 hours'))
				     OR EXISTS (
				            SELECT 1 FROM link_request lr
				             WHERE lr.status = 'ACCEPTED'
				               AND ((lr.requester_id = :viewer AND lr.recipient_id = :target)
				                 OR (lr.requester_id = :target AND lr.recipient_id = :viewer))))
				""")
				.param("viewer", viewer).param("target", target)
				.query((rs, n) -> new ProfileRow(rs.getObject("id", UUID.class), rs.getString("username"),
						rs.getString("display_name"), rs.getString("bio"), rs.getString("avatar_url")))
				.optional();
	}

	/** Il proprio profilo: sempre visibile a se stessi. */
	Optional<ProfileRow> findSelf(UUID userId) {
		return jdbc.sql("SELECT id, username, display_name, bio, avatar_url FROM app_user WHERE id = :id")
				.param("id", userId)
				.query((rs, n) -> new ProfileRow(rs.getObject("id", UUID.class), rs.getString("username"),
						rs.getString("display_name"), rs.getString("bio"), rs.getString("avatar_url")))
				.optional();
	}

	/** Tutti gli interessi della persona, nell'ordine del catalogo. */
	List<SharedInterestResponse> interestsOf(UUID userId) {
		return jdbc.sql("""
				SELECT i.id, i.slug, i.name, c.slug AS category_slug
				  FROM user_interest ui
				  JOIN interest i ON i.id = ui.interest_id
				  JOIN interest_category c ON c.id = i.category_id
				 WHERE ui.user_id = :id
				 ORDER BY c.sort_order, i.sort_order
				""")
				.param("id", userId)
				.query((rs, n) -> new SharedInterestResponse(rs.getObject("id", UUID.class), rs.getString("slug"),
						rs.getString("name"), rs.getString("category_slug")))
				.list();
	}

}
