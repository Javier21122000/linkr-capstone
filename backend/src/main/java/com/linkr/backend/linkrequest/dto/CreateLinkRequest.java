package com.linkr.backend.linkrequest.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Richiesta di Link. Il mittente e' sempre l'utente del JWT: non esiste un campo senderId (campi extra ignorati). */
public record CreateLinkRequest(@NotNull UUID eventId, @NotNull UUID recipientId) {
}
