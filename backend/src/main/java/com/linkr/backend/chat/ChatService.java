package com.linkr.backend.chat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.chat.ChatRepository.ConversationRow;
import com.linkr.backend.chat.ChatRepository.Cursor;
import com.linkr.backend.chat.ChatRepository.MessageRow;
import com.linkr.backend.chat.dto.ConversationResponse;
import com.linkr.backend.chat.dto.MessagePageResponse;
import com.linkr.backend.chat.dto.MessageResponse;
import com.linkr.backend.common.page.PageResponse;
import com.linkr.backend.event.InvalidEventFilterException;
import com.linkr.backend.linkrequest.dto.LinkRequestUserResponse;

/**
 * Chat privata. Una conversazione e' un Link ACCEPTED: nessun Link accettato = nessuna chat. Ogni operazione verifica nel
 * database che l'utente (sempre quello del JWT) faccia parte della conversazione; per ogni altro caso (inesistente, di altri,
 * in attesa, rifiutata) la risposta e' lo stesso 404.
 *
 * I messaggi si scrivono SOLO da qui (REST). Dopo la scrittura si pubblica ChatMessageCreated: il WebSocket lo notifica
 * dopo il commit.
 */
@Service
public class ChatService {

	public static final int MAX_BODY_LENGTH = 2000;
	public static final int DEFAULT_MESSAGES_SIZE = 30;

	private final ChatRepository repository;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public ChatService(ChatRepository repository, ApplicationEventPublisher events, Clock clock) {
		this.repository = repository;
		this.events = events;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<ConversationResponse> listConversations(UUID me, int page, int size) {
		checkPage(page, size);
		long total = repository.countConversations(me);
		List<ConversationResponse> items = repository.findConversations(me, size, (long) page * size).stream()
				.map(row -> toResponse(row, me)).toList();
		return PageResponse.of(items, page, size, total);
	}

	/** Messaggi dal piu' recente. before = id del messaggio piu' vecchio gia' caricato. Si chiede size+1 righe per sapere se ce ne sono altri. */
	@Transactional(readOnly = true)
	public MessagePageResponse messages(UUID me, UUID conversationId, UUID before, int size) {
		if (size < 1 || size > PageResponse.MAX_SIZE) {
			throw new InvalidEventFilterException("'size' must be between 1 and " + PageResponse.MAX_SIZE);
		}
		requireMember(conversationId, me);
		Cursor cursor = null;
		if (before != null) {
			cursor = repository.findCursor(conversationId, before)
					.orElseThrow(() -> new InvalidEventFilterException("'before' must be the id of a message of this conversation"));
		}
		List<MessageRow> rows = repository.findMessages(conversationId, cursor, size + 1);
		boolean hasMore = rows.size() > size;
		List<MessageResponse> items = rows.stream().limit(size).map(row -> toResponse(row, me)).toList();
		return new MessagePageResponse(items, hasMore);
	}

	@Transactional
	public MessageResponse send(UUID me, UUID conversationId, String rawBody) {
		String body = rawBody == null ? "" : rawBody.strip();
		if (body.isEmpty() || body.length() > MAX_BODY_LENGTH) {
			throw new InvalidEventFilterException("'body' must have between 1 and " + MAX_BODY_LENGTH + " characters");
		}
		MessageRow row = repository.insert(conversationId, me, body).orElseThrow(ConversationNotFoundException::new);
		UUID other = repository.findOtherParticipant(conversationId, me).orElseThrow(ConversationNotFoundException::new);

		MessageResponse mine = toResponse(row, me);
		events.publishEvent(new ChatMessageCreated(me, mine, other, toResponse(row, other)));
		return mine;
	}

	/** Segna come letti i messaggi dell'altra persona. Idempotente: ripeterlo non cambia nulla. */
	@Transactional
	public void markRead(UUID me, UUID conversationId) {
		requireMember(conversationId, me);
		repository.markRead(conversationId, me);
	}

	private void requireMember(UUID conversationId, UUID me) {
		repository.findOtherParticipant(conversationId, me).orElseThrow(ConversationNotFoundException::new);
	}

	private void checkPage(int page, int size) {
		if (page < 0) {
			throw new InvalidEventFilterException("'page' must be 0 or greater");
		}
		if (size < 1 || size > PageResponse.MAX_SIZE) {
			throw new InvalidEventFilterException("'size' must be between 1 and " + PageResponse.MAX_SIZE);
		}
	}

	private ConversationResponse toResponse(ConversationRow row, UUID me) {
		return new ConversationResponse(row.id(),
				new LinkRequestUserResponse(row.otherId(), row.otherUsername(), row.otherDisplayName(), row.otherAvatarUrl()),
				row.lastMessage() == null ? null : toResponse(row.lastMessage(), me), row.unreadCount(), local(row.linkedAt()));
	}

	/** readAt si mostra solo a chi ha scritto: per chi riceve non ha significato. */
	private MessageResponse toResponse(MessageRow row, UUID viewer) {
		boolean mine = row.senderId().equals(viewer);
		return new MessageResponse(row.id(), row.conversationId(), row.senderId(), mine, row.body(), local(row.createdAt()),
				mine ? local(row.readAt()) : null);
	}

	private OffsetDateTime local(OffsetDateTime value) {
		return value == null ? null : value.atZoneSameInstant(clock.getZone()).toOffsetDateTime();
	}

}
