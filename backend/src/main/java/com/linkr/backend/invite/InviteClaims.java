package com.linkr.backend.invite;

import java.time.Instant;
import java.util.UUID;

/** Cosa afferma un token d'invito valido: chi ha condiviso quale evento, e fino a quando il token vale. */
record InviteClaims(UUID eventId, UUID senderId, Instant expiresAt) {
}
