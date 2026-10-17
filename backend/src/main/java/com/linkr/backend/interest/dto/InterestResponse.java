package com.linkr.backend.interest.dto;

import java.util.UUID;

/** Interesse nel catalogo. */
public record InterestResponse(UUID id, String slug, String name, int sortOrder) {
}
