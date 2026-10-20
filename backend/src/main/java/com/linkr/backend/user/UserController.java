package com.linkr.backend.user;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.user.dto.DiscoveryResponse;
import com.linkr.backend.user.dto.UpdateDiscoveryRequest;
import com.linkr.backend.user.dto.UserResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	/** Profilo dell'utente autenticato: l'identita' arriva SOLO dal JWT (claim "sub"), mai dalla richiesta. */
	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return userService.getProfile(UUID.fromString(jwt.getSubject()));
	}

	/** Opt-in a Discover People (false di default). Solo per se stessi: l'identita' arriva dal JWT. */
	@PutMapping("/me/discovery")
	public DiscoveryResponse setDiscovery(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateDiscoveryRequest request) {
		return userService.setDiscoverable(UUID.fromString(jwt.getSubject()), request.discoverable());
	}

}
