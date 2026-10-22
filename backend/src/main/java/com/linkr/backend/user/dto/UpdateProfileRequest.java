package com.linkr.backend.user.dto;

import com.linkr.backend.upload.EventImageStorage;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Campi modificabili del proprio profilo (PUT /api/users/me/profile): sostituiscono i precedenti.
 * Non esistono id, email, username, ruolo, password o visibilita': l'utente e' sempre quello del JWT e
 * qualunque campo extra nel JSON viene ignorato (nessun mass assignment).
 * Le regole sul contenuto (caratteri ammessi, lunghezze dopo la pulizia) sono in ProfileText; che l'avatar sia
 * davvero un file caricato lo verifica UserService.
 *
 * avatarUrl: null per rimuoverlo, altrimenti SOLO il percorso restituito da POST /api/uploads/event-images.
 */
public record UpdateProfileRequest(
		@NotNull @Size(max = 200) String displayName,
		@Size(max = 2000) String bio,
		@Size(max = 500) @Pattern(regexp = UpdateProfileRequest.AVATAR_URL_REGEX, message = "must be an uploaded image") String avatarUrl) {

	public static final String AVATAR_URL_REGEX = "^" + EventImageStorage.PUBLIC_PATH + EventImageStorage.FILENAME_REGEX + "$";

}
