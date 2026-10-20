package com.linkr.backend.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.linkr.backend.user.UserRole;

/** Profilo restituito dalle API. Non contiene mai l'hash della password. discoverable: visibile in Discover People (opt-in). */
public record UserResponse(
		UUID id,
		String username,
		String email,
		String displayName,
		String bio,
		String avatarUrl,
		UserRole role,
		OffsetDateTime createdAt,
		boolean discoverable) {
}
