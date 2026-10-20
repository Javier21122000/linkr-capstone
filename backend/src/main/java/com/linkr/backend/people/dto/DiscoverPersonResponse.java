package com.linkr.backend.people.dto;

import java.util.List;
import java.util.UUID;

import com.linkr.backend.linkrequest.LinkStatus;

/**
 * Persona trovata in Discover People. Solo dati pubblici: niente email, bio, ruolo, posizione, eventi e niente interessi
 * non condivisi. sharedInterests e' l'intersezione REALE con gli interessi dell'utente autenticato (sempre almeno uno).
 * linkStatus / linkRequestId hanno lo stesso significato di PersonResponse (la privacy del rifiuto e' la stessa).
 */
public record DiscoverPersonResponse(
		UUID userId,
		String username,
		String displayName,
		String avatarUrl,
		int sharedInterestsCount,
		List<SharedInterestResponse> sharedInterests,
		LinkStatus linkStatus,
		UUID linkRequestId) {
}
