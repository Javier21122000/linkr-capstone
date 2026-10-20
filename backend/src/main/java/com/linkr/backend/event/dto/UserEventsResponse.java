package com.linkr.backend.event.dto;

import java.util.List;

/** Risposta di GET /api/users/me/events: gli eventi creati dall'utente (anche annullati), dal piu' recente per inizio. */
public record UserEventsResponse(List<EventSummaryResponse> events) {
}
