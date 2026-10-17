package com.linkr.backend.linkrequest;

import java.util.UUID;

/** Esistenza e stato (visto da chi guarda) del Link con una persona. Usato dalla discovery persone. */
public record LinkState(UUID requestId, LinkStatus status) {
}
