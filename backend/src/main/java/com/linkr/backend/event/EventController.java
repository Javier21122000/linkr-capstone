package com.linkr.backend.event;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventRequest;
import com.linkr.backend.event.dto.EventSummaryResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/events")
public class EventController {

	private final EventService eventService;
	private final EventManagementService managementService;

	public EventController(EventService eventService, EventManagementService managementService) {
		this.eventService = eventService;
		this.managementService = managementService;
	}

	/** Esempio: GET /api/events?category=MUSIC&from=2026-10-15&to=2026-10-18 (tutti i parametri sono opzionali). */
	@GetMapping
	public List<EventSummaryResponse> list(
			@RequestParam(required = false) EventCategory category,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return eventService.findEvents(category, from, to);
	}

	/**
	 * Pubblico. Senza token: solo eventi PUBLISHED, canEdit=false. Con un token valido: canEdit corretto e, per creatore,
	 * admin e partecipanti, anche gli eventi ANNULLATI. (Un token scaduto o non valido da' 401 come ovunque.)
	 */
	@GetMapping("/{id}")
	public EventDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		if (jwt == null) {
			return eventService.getEvent(id);
		}
		return managementService.detailFor(userId(jwt), id);
	}

	/** Crea un evento COMMUNITY (gli admin possono scegliere "type"). Il creatore e' l'utente del token. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public EventDetailResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EventRequest request) {
		return managementService.create(userId(jwt), request);
	}

	/** Sostituisce i contenuti dell'evento. Solo creatore o admin. */
	@PutMapping("/{id}")
	public EventDetailResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody EventRequest request) {
		return managementService.update(userId(jwt), id, request);
	}

	/** Annullamento logico (status CANCELLED), idempotente. Solo creatore o admin. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		managementService.cancel(userId(jwt), id);
	}

	private UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
