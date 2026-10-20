package com.linkr.backend.chat.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Messaggio visto dall'utente autenticato. mine = lo ho scritto io. readAt = quando l'altra persona lo ha letto
 * (null se non ancora letto, e sempre null per i messaggi che ricevo: la lettura e' un fatto di chi riceve).
 */
public record MessageResponse(
		UUID messageId,
		UUID conversationId,
		UUID senderId,
		boolean mine,
		String body,
		OffsetDateTime createdAt,
		OffsetDateTime readAt) {
}
