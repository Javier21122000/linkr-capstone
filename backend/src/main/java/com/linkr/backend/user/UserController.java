package com.linkr.backend.user;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.user.dto.DiscoveryResponse;
import com.linkr.backend.user.dto.PublicProfileResponse;
import com.linkr.backend.user.dto.UpdateDiscoveryRequest;
import com.linkr.backend.user.dto.UpdateProfileRequest;
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

	/** Nome, bio e avatar del proprio profilo. Solo per se stessi (JWT); id, email, ruolo e username non sono modificabili. */
	@PutMapping("/me/profile")
	public UserResponse updateProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
		return userService.updateProfile(UUID.fromString(jwt.getSubject()), request);
	}

	/** Profilo di un'altra persona: 404 se non esiste o se non hai il diritto di vederlo (vedi ProfileRepository.findVisible). */
	@GetMapping("/{id}/profile")
	public PublicProfileResponse publicProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return userService.getPublicProfile(UUID.fromString(jwt.getSubject()), id);
	}

	/** Opt-in a Discover People (false di default). Solo per se stessi: l'identita' arriva dal JWT. */
	@PutMapping("/me/discovery")
	public DiscoveryResponse setDiscovery(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateDiscoveryRequest request) {
		return userService.setDiscoverable(UUID.fromString(jwt.getSubject()), request.discoverable());
	}

}
