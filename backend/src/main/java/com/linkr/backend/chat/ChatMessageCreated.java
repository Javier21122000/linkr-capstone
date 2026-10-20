package com.linkr.backend.chat;

import java.util.UUID;

import com.linkr.backend.chat.dto.MessageResponse;

/**
 * Evento interno: un messaggio e' stato scritto nel database (nella transazione corrente). Chi lo ascolta con
 * @TransactionalEventListener(AFTER_COMMIT) lo vede SOLO dopo il commit, quindi non si notifica mai un messaggio non salvato.
 * Ogni persona riceve la propria versione del messaggio (il campo "mine" cambia).
 */
public record ChatMessageCreated(UUID senderId, MessageResponse forSender, UUID recipientId, MessageResponse forRecipient) {
}
