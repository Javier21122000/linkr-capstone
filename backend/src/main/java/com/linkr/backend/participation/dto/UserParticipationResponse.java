package com.linkr.backend.participation.dto;

import java.time.OffsetDateTime;

import com.linkr.backend.event.dto.EventSummaryResponse;
import com.linkr.backend.participation.ParticipationStatus;

/** Elemento di GET /api/users/me/participations: la partecipazione piu' i dati essenziali dell'evento. */
public record UserParticipationResponse(
		ParticipationStatus status,
		boolean visibleToOthers,
		OffsetDateTime updatedAt,
		EventSummaryResponse event) {
}
