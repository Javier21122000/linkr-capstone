package com.linkr.backend.invite;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.invite.InviteTokenService.IssuedToken;
import com.linkr.backend.invite.dto.InviteResponse;
import com.linkr.backend.invite.dto.IssuedInviteResponse;
import com.linkr.backend.linkrequest.dto.LinkRequestResponse;

/**
 * Inviti agli eventi. Nessuna rotta e' pubblica (vedi SecurityConfig, "anyRequest"): l'utente arriva sempre dal JWT e non c'e' nessun
 * parametro che permetta di indicare un altro mittente. Il link dell'evento (`/events/{id}`) resta pubblico e non passa di qui.
 */
@RestController
public class InviteController {

	private final InviteService service;

	public InviteController(InviteService service) {
		this.service = service;
	}

	/** Il token di chi condivide l'evento (sempre a proprio nome). Corpo vuoto. */
	@PostMapping("/api/events/{eventId}/invites")
	public IssuedInviteResponse issue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
		IssuedToken issued = service.issue(UUID.fromString(jwt.getSubject()), eventId);
		return new IssuedInviteResponse(issued.token(), issued.expiresAt());
	}

	/** Chi ha condiviso l'evento, se visibile, e lo stato del Link con chi guarda. 404 uniforme in ogni altro caso. */
	@GetMapping("/api/invites/{token}")
	public InviteResponse resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
		return service.resolve(token, UUID.fromString(jwt.getSubject()));
	}

	/** Richiesta Link esplicita al mittente dell'invito (PENDING: deve accettare). */
	@PostMapping("/api/invites/{token}/link-request")
	@ResponseStatus(HttpStatus.CREATED)
	public LinkRequestResponse sendLinkRequest(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
		return service.sendLinkRequest(token, UUID.fromString(jwt.getSubject()));
	}

}
