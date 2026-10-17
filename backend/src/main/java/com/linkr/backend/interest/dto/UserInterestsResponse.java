package com.linkr.backend.interest.dto;

import java.util.List;

/** Risposta di GET e PUT /api/users/me/interests. Ordinata come il catalogo. */
public record UserInterestsResponse(List<UserInterestResponse> interests) {
}
