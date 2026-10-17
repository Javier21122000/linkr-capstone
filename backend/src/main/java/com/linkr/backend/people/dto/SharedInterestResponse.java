package com.linkr.backend.people.dto;

import java.util.UUID;

/** Un interesse che l'utente autenticato ha IN COMUNE con la persona mostrata. */
public record SharedInterestResponse(UUID id, String slug, String name, String categorySlug) {
}
