package com.linkr.backend.linkrequest.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Richiesta di Link. Il mittente e' sempre l'utente del JWT: non esiste un campo senderId (campi extra ignorati).
 * eventId e' facoltativo: con un evento vale il contesto dell'evento, senza evento la richiesta nasce da Discover People.
 */
public record CreateLinkRequest(UUID eventId, @NotNull UUID recipientId) {
}
