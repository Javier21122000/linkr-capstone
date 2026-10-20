package com.linkr.backend.people;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.common.page.PageResponse;
import com.linkr.backend.people.dto.DiscoverPersonResponse;

/** Discover People. Richiede il JWT (vedi SecurityConfig: "anyRequest"). L'utente arriva sempre dal token. */
@RestController
@RequestMapping("/api/people")
public class DiscoverPeopleController {

	private final DiscoverPeopleService service;

	public DiscoverPeopleController(DiscoverPeopleService service) {
		this.service = service;
	}

	/** Esempio: GET /api/people?page=0&size=20 */
	@GetMapping
	public PageResponse<DiscoverPersonResponse> people(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
		return service.findPeople(UUID.fromString(jwt.getSubject()), page, size);
	}

}
