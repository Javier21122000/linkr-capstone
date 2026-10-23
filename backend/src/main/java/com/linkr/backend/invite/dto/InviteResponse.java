package com.linkr.backend.invite.dto;

import java.util.UUID;

import com.linkr.backend.linkrequest.LinkStatus;

/**
 * Chi ha condiviso l'evento, per chi apre il link da autenticato. Solo nome visualizzato e avatar, e solo se il mittente e' visibile
 * (Discover People): niente id, username, email, bio o interessi. `linkStatus` e' lo stato del Link tra chi guarda e il mittente.
 */
public record InviteResponse(UUID eventId, Sender sender, LinkStatus linkStatus, UUID linkRequestId) {

	public record Sender(String displayName, String avatarUrl) {
	}

}
