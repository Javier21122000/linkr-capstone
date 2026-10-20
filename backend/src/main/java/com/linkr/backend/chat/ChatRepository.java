package com.linkr.backend.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL della chat (JdbcClient). La conversazione e' la riga di link_request con status ACCEPTED di cui l'utente e' parte:
 * ogni query lo verifica nella stessa istruzione che legge o scrive, quindi non esiste "controllo e poi scrittura" separati.
 * Tutti i valori sono parametri (:nome); nell'SQL entrano solo frammenti costanti scelti dal codice.
 */
@Repository
public class ChatRepository {

	record MessageRow(UUID id, UUID conversationId, UUID senderId, String body, OffsetDateTime createdAt, OffsetDateTime readAt) {
	}

	record ConversationRow(UUID id, OffsetDateTime linkedAt, UUID otherId, String otherUsername, String otherDisplayName,
			String otherAvatarUrl, MessageRow lastMessage, long unreadCount) {
	}

	/** Posizione di un messaggio, per la paginazione "keyset" (created_at + id). */
	record Cursor(OffsetDateTime createdAt, UUID id) {
	}

	private static final String MEMBER = "lr.status = 'ACCEPTED' AND (lr.requester_id = :me OR lr.recipient_id = :me)";

	private static final String MESSAGE_COLUMNS = "m.id, m.link_request_id, m.sender_id, m.body, m.created_at, m.read_at";

	private final JdbcClient jdbc;

	public ChatRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** L'altra persona della conversazione. Vuoto = la conversazione non esiste o "me" non ne fa parte o il Link non e' ACCEPTED. */
	Optional<UUID> findOtherParticipant(UUID conversationId, UUID me) {
		return jdbc.sql("SELECT CASE WHEN lr.requester_id = :me THEN lr.recipient_id ELSE lr.requester_id END "
						+ "FROM link_request lr WHERE lr.id = :c AND " + MEMBER)
				.param("c", conversationId).param("me", me).query(UUID.class).optional();
	}

	long countConversations(UUID me) {
		return jdbc.sql("SELECT count(*) FROM link_request lr WHERE " + MEMBER)
				.param("me", me).query(Long.class).single();
	}

	/** Conversazioni con ultimo messaggio e non letti, in UNA query. Prima quelle con attivita' piu' recente. */
	List<ConversationRow> findConversations(UUID me, int size, long offset) {
		return jdbc.sql("""
				SELECT lr.id, lr.responded_at AS linked_at,
				       o.id AS other_id, o.username AS other_username, o.display_name AS other_display_name,
				       o.avatar_url AS other_avatar_url,
				       lm.id AS last_id, lm.sender_id AS last_sender_id, lm.body AS last_body,
				       lm.created_at AS last_created_at, lm.read_at AS last_read_at,
				       (SELECT count(*) FROM chat_message u
				         WHERE u.link_request_id = lr.id AND u.sender_id <> :me AND u.read_at IS NULL) AS unread_count
				  FROM link_request lr
				  JOIN app_user o ON o.id = CASE WHEN lr.requester_id = :me THEN lr.recipient_id ELSE lr.requester_id END
				  LEFT JOIN LATERAL (
				        SELECT m.id, m.sender_id, m.body, m.created_at, m.read_at
				          FROM chat_message m
				         WHERE m.link_request_id = lr.id
				         ORDER BY m.created_at DESC, m.id DESC
				         LIMIT 1) lm ON true
				 WHERE\s""" + MEMBER + """
				 ORDER BY COALESCE(lm.created_at, lr.responded_at) DESC, lr.id DESC
				 LIMIT :size OFFSET :offset
				""")
				.param("me", me).param("size", size).param("offset", offset)
				.query((rs, n) -> {
					UUID conversationId = rs.getObject("id", UUID.class);
					UUID lastId = rs.getObject("last_id", UUID.class);
					MessageRow last = lastId == null ? null
							: new MessageRow(lastId, conversationId, rs.getObject("last_sender_id", UUID.class),
									rs.getString("last_body"), rs.getObject("last_created_at", OffsetDateTime.class),
									rs.getObject("last_read_at", OffsetDateTime.class));
					return new ConversationRow(conversationId, rs.getObject("linked_at", OffsetDateTime.class),
							rs.getObject("other_id", UUID.class), rs.getString("other_username"),
							rs.getString("other_display_name"), rs.getString("other_avatar_url"), last,
							rs.getLong("unread_count"));
				}).list();
	}

	Optional<Cursor> findCursor(UUID conversationId, UUID messageId) {
		return jdbc.sql("SELECT created_at, id FROM chat_message WHERE id = :id AND link_request_id = :c")
				.param("id", messageId).param("c", conversationId)
				.query((rs, n) -> new Cursor(rs.getObject("created_at", OffsetDateTime.class), rs.getObject("id", UUID.class)))
				.optional();
	}

	/** Messaggi dal piu' recente, solo quelli PRECEDENTI al cursore se presente. Usa idx_chat_message_conversation. */
	List<MessageRow> findMessages(UUID conversationId, Cursor before, int limit) {
		var statement = jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM chat_message m WHERE m.link_request_id = :c"
						+ (before == null ? "" : " AND (m.created_at, m.id) < (:beforeAt, :beforeId)")
						+ " ORDER BY m.created_at DESC, m.id DESC LIMIT :limit")
				.param("c", conversationId).param("limit", limit);
		if (before != null) {
			statement = statement.param("beforeAt", before.createdAt()).param("beforeId", before.id());
		}
		return statement.query(this::mapMessage).list();
	}

	/**
	 * Scrive il messaggio SOLO se la conversazione e' un Link ACCEPTED di cui "me" fa parte: il controllo e la scrittura sono
	 * la stessa istruzione. Vuoto = conversazione non accessibile (nessuna riga scritta).
	 */
	Optional<MessageRow> insert(UUID conversationId, UUID me, String body) {
		return jdbc.sql("INSERT INTO chat_message (link_request_id, sender_id, body) "
						+ "SELECT lr.id, :me, :body FROM link_request lr WHERE lr.id = :c AND " + MEMBER
						+ " RETURNING id, link_request_id, sender_id, body, created_at, read_at")
				.param("c", conversationId).param("me", me).param("body", body)
				.query((rs, n) -> new MessageRow(rs.getObject("id", UUID.class), rs.getObject("link_request_id", UUID.class),
						rs.getObject("sender_id", UUID.class), rs.getString("body"),
						rs.getObject("created_at", OffsetDateTime.class), rs.getObject("read_at", OffsetDateTime.class)))
				.optional();
	}

	/** Segna come letti i messaggi dell'ALTRA persona ancora da leggere. Restituisce quanti. Idempotente. */
	int markRead(UUID conversationId, UUID me) {
		return jdbc.sql("UPDATE chat_message SET read_at = clock_timestamp() "
						+ "WHERE link_request_id = :c AND sender_id <> :me AND read_at IS NULL")
				.param("c", conversationId).param("me", me).update();
	}

	private MessageRow mapMessage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new MessageRow(rs.getObject("id", UUID.class), rs.getObject("link_request_id", UUID.class),
				rs.getObject("sender_id", UUID.class), rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("read_at", OffsetDateTime.class));
	}

}
