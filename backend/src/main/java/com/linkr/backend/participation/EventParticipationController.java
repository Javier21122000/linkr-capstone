package com.linkr.backend.participation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.participation.dto.ParticipationResponse;
import com.linkr.backend.participation.dto.UpdateParticipationRequest;
import com.linkr.backend.participation.dto.UserParticipationsResponse;

import jakarta.validation.Valid;

/**
 * Partecipazioni dell'utente autenticato. Tutte le rotte richiedono il JWT (vedi SecurityConfig):
 * l'identita' arriva solo dal token, quindi nessuno puo' leggere o modificare le partecipazioni altrui.
 */
@RestController
@RequestMapping("/api")
public class EventParticipationController {

	private final EventParticipationService participationService;

	public EventParticipationController(EventParticipationService participationService) {
		this.participationService = participationService;
	}

	@PutMapping("/events/{id}/participation")
	public ParticipationResponse declare(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody UpdateParticipationRequest request) {
		return participationService.declare(userId(jwt), id, request.status(), request.visibleToOthers());
	}

	@GetMapping("/events/{id}/participation")
	public ParticipationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return participationService.get(userId(jwt), id);
	}

	@DeleteMapping("/events/{id}/participation")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		participationService.remove(userId(jwt), id);
	}

	@GetMapping("/users/me/participations")
	public UserParticipationsResponse list(@AuthenticationPrincipal Jwt jwt) {
		return participationService.list(userId(jwt));
	}

	private UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
