package com.linkr.backend.invite.dto;

import java.time.Instant;

/** Il token da aggiungere al link dell'evento (`?i=<token>`) e la sua scadenza. */
public record IssuedInviteResponse(String token, Instant expiresAt) {
}
