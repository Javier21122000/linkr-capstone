package com.linkr.backend.event.dto;

import java.util.UUID;

/** Chi ha creato l'evento: solo dati pubblici minimi, mai email o ruolo. */
public record EventCreatorResponse(UUID userId, String username, String displayName, String avatarUrl) {
}
