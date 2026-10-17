package com.linkr.backend.linkrequest.dto;

import java.util.UUID;

/** Dati pubblici minimi dell'ALTRA persona. Mai email, ruolo o altri dati personali. */
public record LinkRequestUserResponse(UUID userId, String username, String displayName, String avatarUrl) {
}
