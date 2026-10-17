package com.linkr.backend.participation.dto;

import com.linkr.backend.participation.ParticipationStatus;

import jakarta.validation.constraints.NotNull;

/**
 * Dichiarazione dell'utente. Entrambi i campi sono obbligatori: la visibilita' non ha un default implicito,
 * l'utente deve sceglierla. Non c'e' un campo userId: l'utente e' sempre quello del JWT (campi extra ignorati).
 */
public record UpdateParticipationRequest(
		@NotNull ParticipationStatus status,
		@NotNull Boolean visibleToOthers) {
}
