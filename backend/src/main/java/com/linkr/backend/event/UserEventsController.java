package com.linkr.backend.event;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.event.dto.UserEventsResponse;

/** Eventi creati dall'utente autenticato. Richiede il JWT (non e' sotto /api/events/*, che e' pubblico in GET). */
@RestController
@RequestMapping("/api/users/me/events")
public class UserEventsController {

	private final EventManagementService managementService;

	public UserEventsController(EventManagementService managementService) {
		this.managementService = managementService;
	}

	@GetMapping
	public UserEventsResponse myEvents(@AuthenticationPrincipal Jwt jwt) {
		return managementService.myEvents(UUID.fromString(jwt.getSubject()));
	}

}
