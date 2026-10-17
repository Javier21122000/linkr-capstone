package com.linkr.backend.auth.dto;

import com.linkr.backend.user.dto.UserResponse;

/** Risposta di registrazione e login: token da inviare come "Authorization: Bearer <accessToken>". */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
}
