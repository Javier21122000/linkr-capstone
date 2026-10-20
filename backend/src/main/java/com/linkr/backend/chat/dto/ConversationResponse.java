package com.linkr.backend.chat.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.linkr.backend.linkrequest.dto.LinkRequestUserResponse;

/**
 * Conversazione = un Link ACCEPTED. conversationId e' l'id della richiesta Link (lo stesso di GET /api/link-requests).
 * lastMessage e' null se nessuno ha ancora scritto. unreadCount = messaggi dell'altra persona non ancora letti.
 * linkedAt = quando il Link e' stato accettato.
 */
public record ConversationResponse(
		UUID conversationId,
		LinkRequestUserResponse otherUser,
		MessageResponse lastMessage,
		long unreadCount,
		OffsetDateTime linkedAt) {
}
