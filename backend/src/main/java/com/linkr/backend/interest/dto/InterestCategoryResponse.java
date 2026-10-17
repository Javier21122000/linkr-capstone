package com.linkr.backend.interest.dto;

import java.util.List;
import java.util.UUID;

/** Categoria del catalogo con i suoi interessi, entrambi in ordine. */
public record InterestCategoryResponse(UUID id, String slug, String name, int sortOrder,
		List<InterestResponse> interests) {
}
