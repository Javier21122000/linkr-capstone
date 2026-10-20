package com.linkr.backend.chat;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.linkr.backend.chat.dto.MessageResponse;

/**
 * Notifica via WebSocket i nuovi messaggi, SOLO DOPO il commit del database: se la transazione fallisce o viene annullata
 * non parte nessuna notifica, quindi nessun client vede un messaggio che non esiste. Va a entrambe le persone (anche il
 * mittente, con piu' schede aperte), ciascuna con la propria versione (campo "mine").
 *
 * Una notifica persa non e' un errore: il messaggio e' gia' salvato e il client lo ritrova via REST. Per questo un guasto
 * del broker non puo' far fallire l'invio.
 */
@Component
class ChatMessagePusher {

	/** Destinazione privata: il client si iscrive a "/user" + questa. */
	static final String QUEUE = "/queue/messages";

	private static final Logger log = LoggerFactory.getLogger(ChatMessagePusher.class);

	private final SimpMessagingTemplate messagingTemplate;

	ChatMessagePusher(SimpMessagingTemplate messagingTemplate) {
		this.messagingTemplate = messagingTemplate;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onMessageCreated(ChatMessageCreated event) {
		push(event.recipientId(), event.forRecipient());
		push(event.senderId(), event.forSender());
	}

	private void push(UUID userId, MessageResponse message) {
		try {
			messagingTemplate.convertAndSendToUser(userId.toString(), QUEUE, message);
		}
		catch (RuntimeException e) {
			log.warn("Could not push a chat notification", e);
		}
	}

}
