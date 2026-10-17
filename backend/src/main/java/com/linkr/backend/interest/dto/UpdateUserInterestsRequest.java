package com.linkr.backend.interest.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Nuova selezione completa dell'utente (sostituisce la precedente). Da 0 a 15 interessi.
 * Non c'e' un campo userId: l'utente e' sempre quello del JWT. Eventuali campi extra vengono ignorati.
 * Duplicati e ID inesistenti vengono rifiutati dal service.
 */
public record UpdateUserInterestsRequest(
		@NotNull @Size(max = UpdateUserInterestsRequest.MAX_INTERESTS) List<@NotNull UUID> interestIds) {

	public static final int MAX_INTERESTS = 15;

}
