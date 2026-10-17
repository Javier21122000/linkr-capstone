package com.linkr.backend.people.dto;

import java.util.List;
import java.util.UUID;

import com.linkr.backend.participation.ParticipationStatus;

/**
 * Persona visibile a un evento. Contiene SOLO dati pubblici: niente email, bio, ruolo, altri eventi
 * e niente interessi non condivisi.
 */
public record PersonResponse(
		UUID userId,
		String username,
		String displayName,
		String avatarUrl,
		ParticipationStatus status,
		int sharedInterestsCount,
		List<SharedInterestResponse> sharedInterests) {
}
