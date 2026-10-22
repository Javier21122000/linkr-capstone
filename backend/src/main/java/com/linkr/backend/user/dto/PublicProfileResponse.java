package com.linkr.backend.user.dto;

import java.util.List;
import java.util.UUID;

import com.linkr.backend.linkrequest.LinkStatus;
import com.linkr.backend.people.dto.SharedInterestResponse;

/**
 * Profilo di un'altra persona. Solo dati pubblici: niente email, ruolo, data di iscrizione, visibilita' o partecipazioni.
 * interests = tutti gli interessi della persona; sharedInterests = quelli che hai anche tu (sottoinsieme).
 * linkStatus/linkRequestId come in Discover People (NONE se non c'e' nessuna richiesta, o se il profilo e' il tuo).
 */
public record PublicProfileResponse(
		UUID userId,
		String username,
		String displayName,
		String bio,
		String avatarUrl,
		List<SharedInterestResponse> interests,
		List<SharedInterestResponse> sharedInterests,
		LinkStatus linkStatus,
		UUID linkRequestId) {
}
