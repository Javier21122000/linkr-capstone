package com.linkr.backend.interest;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.interest.dto.UpdateUserInterestsRequest;
import com.linkr.backend.interest.dto.UserInterestsResponse;

import jakarta.validation.Valid;

/** Interessi dell'utente autenticato. L'identita' arriva solo dal JWT: nessuno puo' toccare gli interessi altrui. */
@RestController
@RequestMapping("/api/users/me/interests")
public class UserInterestController {

	private final UserInterestService userInterestService;

	public UserInterestController(UserInterestService userInterestService) {
		this.userInterestService = userInterestService;
	}

	@GetMapping
	public UserInterestsResponse get(@AuthenticationPrincipal Jwt jwt) {
		return userInterestService.getInterests(UUID.fromString(jwt.getSubject()));
	}

	@PutMapping
	public UserInterestsResponse replace(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody UpdateUserInterestsRequest request) {
		return userInterestService.replaceInterests(UUID.fromString(jwt.getSubject()), request.interestIds());
	}

}
