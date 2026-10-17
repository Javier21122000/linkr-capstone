package com.linkr.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Dati di registrazione. Non c'e' un campo "role": se il client ne invia uno viene ignorato,
 * e ogni nuovo utente e' sempre USER.
 * La password e' limitata a 72 caratteri perche' BCrypt non usa i byte oltre il 72esimo.
 */
public record RegisterRequest(
		@NotBlank @Size(min = 3, max = 30)
		@Pattern(regexp = "[A-Za-z0-9_.]+", message = "must contain only letters, digits, '_' and '.'")
		String username,
		@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(min = 8, max = 72) String password,
		@NotBlank @Size(max = 60) String displayName) {
}
