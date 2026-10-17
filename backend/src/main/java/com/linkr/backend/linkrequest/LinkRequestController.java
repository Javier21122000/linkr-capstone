package com.linkr.backend.linkrequest;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.common.page.PageResponse;
import com.linkr.backend.event.InvalidEventFilterException;
import com.linkr.backend.linkrequest.LinkRequestRepository.Direction;
import com.linkr.backend.linkrequest.dto.CreateLinkRequest;
import com.linkr.backend.linkrequest.dto.LinkRequestResponse;

import jakarta.validation.Valid;

/**
 * Richieste Link dell'utente autenticato. Tutte le rotte richiedono il JWT (vedi SecurityConfig, "anyRequest"):
 * l'utente arriva sempre dal token, quindi nessuno puo' vedere o rispondere alle richieste altrui.
 */
@RestController
@RequestMapping("/api/link-requests")
public class LinkRequestController {

	private final LinkRequestService service;

	public LinkRequestController(LinkRequestService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public LinkRequestResponse send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateLinkRequest request) {
		return service.send(userId(jwt), request.eventId(), request.recipientId());
	}

	/**
	 * Esempio: GET /api/link-requests?direction=incoming&status=PENDING&page=0&size=20
	 * direction: incoming (default) | outgoing | all. status (facoltativo): PENDING | ACCEPTED | DECLINED, applicato allo
	 * stato VISTO dall'utente: per il mittente un rifiuto conta come PENDING.
	 */
	@GetMapping
	public PageResponse<LinkRequestResponse> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "incoming") String direction,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
		return service.list(userId(jwt), parseDirection(direction), parseStatus(status), page, size);
	}

	@PostMapping("/{id}/accept")
	public LinkRequestResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.accept(userId(jwt), id);
	}

	@PostMapping("/{id}/decline")
	public LinkRequestResponse decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.decline(userId(jwt), id);
	}

	private UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	private Direction parseDirection(String value) {
		return switch (value) {
			case "incoming" -> Direction.INCOMING;
			case "outgoing" -> Direction.OUTGOING;
			case "all" -> Direction.ALL;
			default -> throw new InvalidEventFilterException("'direction' must be 'incoming', 'outgoing' or 'all'");
		};
	}

	private LinkRequestStatus parseStatus(String value) {
		if (value == null) {
			return null;
		}
		try {
			return LinkRequestStatus.valueOf(value);
		}
		catch (IllegalArgumentException e) {
			throw new InvalidEventFilterException("'status' must be PENDING, ACCEPTED or DECLINED");
		}
	}

}
