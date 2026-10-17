package com.linkr.backend.people.dto;

import java.util.List;
import java.util.UUID;

import com.linkr.backend.linkrequest.LinkStatus;
import com.linkr.backend.participation.ParticipationStatus;

/**
 * Persona visibile a un evento. Contiene SOLO dati pubblici: niente email, bio, ruolo, altri eventi
 * e niente interessi non condivisi.
 *
 * linkStatus (NONE | OUTGOING_PENDING | INCOMING_PENDING | INCOMING_DECLINED | ACCEPTED) dice se mostrare "Invia Link":
 * solo con NONE. linkRequestId e' l'id della richiesta (null con NONE): serve a rispondere a INCOMING_PENDING.
 * Per il mittente un rifiuto resta OUTGOING_PENDING (il rifiuto non gli viene rivelato).
 */
public record PersonResponse(
		UUID userId,
		String username,
		String displayName,
		String avatarUrl,
		ParticipationStatus status,
		int sharedInterestsCount,
		List<SharedInterestResponse> sharedInterests,
		LinkStatus linkStatus,
		UUID linkRequestId) {
}
