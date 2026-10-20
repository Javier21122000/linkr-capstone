package com.linkr.backend.linkrequest;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL delle richieste Link (JdbcClient). Le scritture sono comandi atomici (INSERT ... ON CONFLICT,
 * UPDATE condizionale): la correttezza non dipende da "leggi e poi scrivi" in Java, ma dai vincoli del database.
 * Tutti i valori sono parametri (:nome); nell'SQL entrano solo frammenti costanti scelti dal codice.
 */
@Repository
public class LinkRequestRepository {

	/** Direzione richiesta in un elenco. */
	public enum Direction {
		INCOMING("lr.recipient_id = :me"),
		OUTGOING("lr.requester_id = :me"),
		ALL("(lr.requester_id = :me OR lr.recipient_id = :me)");

		private final String predicate;

		Direction(String predicate) {
			this.predicate = predicate;
		}
	}

	/** Riga di richiesta GIA' proiettata per chi guarda (vedi PROJECTION). */
	record Row(UUID id, String status, String direction, OffsetDateTime createdAt, OffsetDateTime respondedAt,
			UUID eventId, String eventTitle, UUID otherId, String otherUsername, String otherDisplayName,
			String otherAvatarUrl) {
	}

	/**
	 * Proiezione di visibilita': per il MITTENTE un DECLINED diventa PENDING e respondedAt sparisce.
	 * E' l'UNICO punto in cui si applica la regola, quindi tutti gli elenchi e i filtri sono coerenti.
	 */
	private static final String PROJECTION = """
			SELECT lr.id,
			       CASE WHEN lr.requester_id = :me AND lr.status = 'DECLINED' THEN 'PENDING' ELSE lr.status END AS visible_status,
			       CASE WHEN lr.requester_id = :me AND lr.status = 'DECLINED' THEN NULL ELSE lr.responded_at END AS visible_responded_at,
			       CASE WHEN lr.requester_id = :me THEN 'OUTGOING' ELSE 'INCOMING' END AS direction,
			       lr.created_at, lr.event_id, e.title AS event_title,
			       o.id AS other_id, o.username AS other_username, o.display_name AS other_display_name,
			       o.avatar_url AS other_avatar_url
			  FROM link_request lr
			  JOIN app_user o ON o.id = CASE WHEN lr.requester_id = :me THEN lr.recipient_id ELSE lr.requester_id END
			  LEFT JOIN event e ON e.id = lr.event_id
			""";

	private final JdbcClient jdbc;

	public LinkRequestRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---------- invio ----------

	/**
	 * Blocca la riga dell'utente mittente fino a fine transazione: i suoi invii simultanei vengono messi in fila,
	 * quindi i conteggi anti-spam non si possono aggirare con richieste parallele.
	 * NO KEY UPDATE non blocca i controlli delle chiavi esterne di altri inserimenti. Vuoto = utente inesistente.
	 */
	boolean lockSender(UUID userId) {
		return jdbc.sql("SELECT id FROM app_user WHERE id = :id FOR NO KEY UPDATE")
				.param("id", userId).query(UUID.class).optional().isPresent();
	}

	/**
	 * Blocca le righe dei DUE utenti (mittente e destinatario) fino a fine transazione, sempre in ordine di id: due invii
	 * incrociati (A->B e B->A) non possono andare in deadlock. Serve alla discovery: il destinatario non puo' nascondersi
	 * "a meta'" dell'invio e i conteggi anti-spam del mittente restano serializzati. Restituisce gli id trovati.
	 */
	Set<UUID> lockUsers(UUID a, UUID b) {
		return new HashSet<>(jdbc.sql("SELECT id FROM app_user WHERE id IN (:a, :b) ORDER BY id FOR NO KEY UPDATE")
				.param("a", a).param("b", b).query(UUID.class).list());
	}

	/** Visibilita' in Discover People (opt-in). Da chiamare dopo lockUsers. */
	boolean isDiscoverable(UUID userId) {
		return jdbc.sql("SELECT discoverable FROM app_user WHERE id = :id")
				.param("id", userId).query(Boolean.class).optional().orElse(false);
	}

	/** Le due persone hanno almeno un interesse in comune (intersezione reale di user_interest). */
	boolean sharesInterest(UUID a, UUID b) {
		return jdbc.sql("""
				SELECT 1 FROM user_interest x JOIN user_interest y ON y.interest_id = x.interest_id
				 WHERE x.user_id = :a AND y.user_id = :b LIMIT 1
				""")
				.param("a", a).param("b", b).query(Integer.class).optional().isPresent();
	}

	boolean hasParticipation(UUID userId, UUID eventId) {
		return jdbc.sql("SELECT 1 FROM event_participation WHERE user_id = :u AND event_id = :e")
				.param("u", userId).param("e", eventId).query(Integer.class).optional().isPresent();
	}

	/**
	 * Il destinatario partecipa all'evento ed e' VISIBILE. FOR SHARE blocca la sua riga di partecipazione fino a fine
	 * transazione: non puo' diventare privato "a meta'" dell'invio.
	 */
	boolean isVisibleParticipant(UUID userId, UUID eventId) {
		return jdbc.sql("""
				SELECT 1 FROM event_participation
				 WHERE user_id = :u AND event_id = :e AND visible_to_others
				   FOR SHARE
				""")
				.param("u", userId).param("e", eventId).query(Integer.class).optional().isPresent();
	}

	/** Richieste inviate che per il mittente sono "in attesa": PENDING e DECLINED (il rifiuto gli e' nascosto). */
	long countAwaitingAnswer(UUID requesterId) {
		return jdbc.sql("SELECT count(*) FROM link_request WHERE requester_id = :me AND status IN ('PENDING', 'DECLINED')")
				.param("me", requesterId).query(Long.class).single();
	}

	long countCreatedSince(UUID requesterId, OffsetDateTime since) {
		return jdbc.sql("SELECT count(*) FROM link_request WHERE requester_id = :me AND created_at >= :since")
				.param("me", requesterId).param("since", since).query(Long.class).single();
	}

	/**
	 * Inserisce la richiesta. ON CONFLICT DO NOTHING: se esiste gia' una richiesta tra le due persone (indice univoco
	 * sulla coppia non ordinata) non si scrive nulla e non c'e' nessuna eccezione da interpretare. Vuoto = duplicato.
	 */
	Optional<UUID> insert(UUID requesterId, UUID recipientId, UUID eventId) {
		return jdbc.sql("""
				INSERT INTO link_request (requester_id, recipient_id, event_id)
				VALUES (:requester, :recipient, :event)
				ON CONFLICT DO NOTHING
				RETURNING id
				""")
				.param("requester", requesterId).param("recipient", recipientId).param("event", eventId)
				.query(UUID.class).optional();
	}

	// ---------- risposta ----------

	/** PENDING -> nuovo stato, SOLO se la richiesta e' indirizzata a "me" ed e' ancora in attesa. true = aggiornata. */
	boolean answer(UUID id, UUID recipientId, LinkRequestStatus newStatus) {
		return jdbc.sql("""
				UPDATE link_request
				   SET status = :status, responded_at = now(), updated_at = now()
				 WHERE id = :id AND recipient_id = :me AND status = 'PENDING'
				""")
				.param("status", newStatus.name()).param("id", id).param("me", recipientId)
				.update() == 1;
	}

	/** Stato reale di una richiesta indirizzata a "me" (serve solo al destinatario, per rispondere in modo coerente). */
	Optional<LinkRequestStatus> findStatusForRecipient(UUID id, UUID recipientId) {
		return jdbc.sql("SELECT status FROM link_request WHERE id = :id AND recipient_id = :me")
				.param("id", id).param("me", recipientId)
				.query((rs, n) -> LinkRequestStatus.valueOf(rs.getString("status"))).optional();
	}

	// ---------- lettura ----------

	/** Una richiesta, solo se "me" ne e' mittente o destinatario, gia' proiettata per "me". */
	Optional<Row> findOne(UUID id, UUID me) {
		return jdbc.sql(PROJECTION + " WHERE lr.id = :id AND (lr.requester_id = :me OR lr.recipient_id = :me)")
				.param("id", id).param("me", me).query(this::mapRow).optional();
	}

	long count(UUID me, Direction direction, LinkRequestStatus visibleStatus) {
		return jdbc.sql("SELECT count(*) FROM (" + PROJECTION + " WHERE " + direction.predicate + ") t"
						+ statusFilter(visibleStatus))
				.params(params(me, visibleStatus)).query(Long.class).single();
	}

	List<Row> findPage(UUID me, Direction direction, LinkRequestStatus visibleStatus, int size, long offset) {
		return jdbc.sql("SELECT * FROM (" + PROJECTION + " WHERE " + direction.predicate + ") t"
						+ statusFilter(visibleStatus)
						+ " ORDER BY t.created_at DESC, t.id DESC LIMIT :size OFFSET :offset")
				.params(params(me, visibleStatus)).param("size", size).param("offset", offset)
				.query(this::mapRow).list();
	}

	/**
	 * Stato del Link tra "me" e ciascuna persona indicata, con UNA query (nessun N+1). Per la discovery persone.
	 * Le persone senza richieste non compaiono nella mappa (= NONE).
	 */
	public Map<UUID, LinkState> findStates(UUID me, Collection<UUID> otherIds) {
		if (otherIds.isEmpty()) {
			return Map.of();
		}
		record StateRow(UUID otherId, LinkState state) {
		}
		return jdbc.sql("""
				SELECT lr.id, lr.status, (lr.requester_id = :me) AS outgoing,
				       CASE WHEN lr.requester_id = :me THEN lr.recipient_id ELSE lr.requester_id END AS other_id
				  FROM link_request lr
				 WHERE (lr.requester_id = :me AND lr.recipient_id IN (:ids))
				    OR (lr.recipient_id = :me AND lr.requester_id IN (:ids))
				""")
				.param("me", me).param("ids", otherIds)
				.query((rs, n) -> new StateRow(rs.getObject("other_id", UUID.class),
						new LinkState(rs.getObject("id", UUID.class),
								LinkStatus.of(rs.getBoolean("outgoing"), LinkRequestStatus.valueOf(rs.getString("status"))))))
				.list().stream()
				.collect(java.util.stream.Collectors.toMap(StateRow::otherId, StateRow::state));
	}

	private static String statusFilter(LinkRequestStatus visibleStatus) {
		return visibleStatus == null ? "" : " WHERE t.visible_status = :visibleStatus";
	}

	private static Map<String, Object> params(UUID me, LinkRequestStatus visibleStatus) {
		Map<String, Object> params = new HashMap<>();
		params.put("me", me);
		if (visibleStatus != null) {
			params.put("visibleStatus", visibleStatus.name());
		}
		return params;
	}

	private Row mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("visible_status"), rs.getString("direction"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getObject("visible_responded_at", OffsetDateTime.class),
				rs.getObject("event_id", UUID.class), rs.getString("event_title"),
				rs.getObject("other_id", UUID.class), rs.getString("other_username"),
				rs.getString("other_display_name"), rs.getString("other_avatar_url"));
	}

}
