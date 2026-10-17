package com.linkr.backend.interest.dto;

import java.util.UUID;

/** Interesse scelto da un utente: include la categoria per comodita' del frontend. */
public record UserInterestResponse(UUID id, String slug, String name, UUID categoryId, String categorySlug) {
}
