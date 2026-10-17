package com.linkr.backend.linkrequest.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Richiesta Link vista dall'utente autenticato.
 *  - direction: INCOMING (ricevuta) o OUTGOING (inviata);
 *  - status: per il MITTENTE un rifiuto appare come PENDING (e respondedAt resta null); per il destinatario e' reale;
 *  - eventId / eventTitle: contesto, null se l'evento non esiste piu';
 *  - otherUser: l'altra persona.
 */
public record LinkRequestResponse(
		UUID requestId,
		String status,
		String direction,
		OffsetDateTime createdAt,
		OffsetDateTime respondedAt,
		UUID eventId,
		String eventTitle,
		LinkRequestUserResponse otherUser) {
}
