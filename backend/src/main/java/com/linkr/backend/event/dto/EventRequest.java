package com.linkr.backend.event.dto;

import java.time.OffsetDateTime;

import com.linkr.backend.event.EventCategory;
import com.linkr.backend.event.EventType;
import com.linkr.backend.upload.EventImageStorage;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Dati per creare (POST) o sostituire (PUT) un evento. Non esistono campi per creatore, stato o "demo":
 * il creatore e' sempre l'utente del JWT e un evento nuovo e' sempre PUBLISHED e non demo (campi extra ignorati).
 * Le regole tra piu' campi (date nel futuro, fine dopo inizio, immagine con testo alternativo) sono in EventManagementService.
 * imageUrl: URL esterno https oppure immagine caricata con POST /api/uploads/event-images (vedi IMAGE_URL_REGEX).
 * Le coordinate devono cadere nell'area di Milano: LINKR mostra solo eventi della citta'.
 */
public record EventRequest(
		@NotBlank @Size(min = 3, max = 120) String title,
		@NotBlank @Size(max = 5000) String description,
		@NotNull EventCategory category,
		@NotNull OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		@NotBlank @Size(max = 120) String venueName,
		@NotBlank @Size(max = 200) String address,
		@NotBlank @Size(max = 60) String neighbourhood,
		@NotNull @DecimalMin("45.35") @DecimalMax("45.60") Double latitude,
		@NotNull @DecimalMin("8.95") @DecimalMax("9.45") Double longitude,
		@Size(max = 500) @Pattern(regexp = EventRequest.IMAGE_URL_REGEX, message = "must be an https URL or an uploaded image") String imageUrl,
		@Size(max = 200) String imageAlt,
		@Size(max = 120) String imageCredit,
		/** Solo per gli amministratori. Per gli utenti normali puo' mancare o essere COMMUNITY. */
		EventType type) {

	/**
	 * imageUrl ammessi: un URL ESTERNO https, oppure UNA SOLA forma interna, quella restituita da POST /api/uploads/event-images
	 * ("/api/uploads/event-images/<uuid>.<jpg|png|webp>"). Qualsiasi altro percorso relativo, http:// o "//host" e' rifiutato.
	 * Che il file interno esista davvero lo verifica EventManagementService.
	 */
	public static final String IMAGE_URL_REGEX = "^(?:https://\\S+|" + EventImageStorage.PUBLIC_PATH + EventImageStorage.FILENAME_REGEX + ")$";

}
