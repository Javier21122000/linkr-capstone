package com.linkr.backend.participation.dto;

import java.util.List;

/** Risposta di GET /api/users/me/participations, ordinata per data di inizio dell'evento. */
public record UserParticipationsResponse(List<UserParticipationResponse> participations) {
}
