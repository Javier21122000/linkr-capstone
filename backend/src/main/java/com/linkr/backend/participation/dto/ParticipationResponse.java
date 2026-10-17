package com.linkr.backend.participation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.linkr.backend.participation.ParticipationStatus;

/** Partecipazione dell'utente autenticato a un evento. */
public record ParticipationResponse(
		UUID eventId,
		ParticipationStatus status,
		boolean visibleToOthers,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {
}
