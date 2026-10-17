package com.linkr.backend.people;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.common.page.PageResponse;
import com.linkr.backend.people.dto.PersonResponse;

/**
 * Persone interessate a un evento. Richiede il JWT (vedi SecurityConfig: "/api/events/{id}/people" ha due segmenti,
 * quindi non rientra nel pattern pubblico "/api/events/*"). L'utente arriva sempre dal token.
 */
@RestController
@RequestMapping("/api/events")
public class PeopleController {

	private final PeopleService peopleService;

	public PeopleController(PeopleService peopleService) {
		this.peopleService = peopleService;
	}

	/** Esempio: GET /api/events/{id}/people?sort=recent&page=0&size=20 */
	@GetMapping("/{id}/people")
	public PageResponse<PersonResponse> people(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestParam(defaultValue = "shared") String sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PeopleService.DEFAULT_SIZE) int size) {
		return peopleService.findPeople(UUID.fromString(jwt.getSubject()), id, PeopleSort.parse(sort), page, size);
	}

}
