package com.linkr.backend.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.linkr.backend.event.EventCategory;
import com.linkr.backend.event.EventStatus;
import com.linkr.backend.event.EventType;

/**
 * Dati completi di un evento per la pagina di dettaglio.
 *  - status: PUBLISHED oppure CANCELLED (un evento annullato e' visibile solo a creatore, amministratori e partecipanti);
 *  - type: OFFICIAL o COMMUNITY;
 *  - creator: chi l'ha creato, null per gli eventi ufficiali/demo senza creatore;
 *  - canEdit: true solo se chi guarda puo' modificarlo ORA (creatore o admin, evento pubblicato e non terminato).
 *    Per chi non e' autenticato e' sempre false.
 */
public record EventDetailResponse(
		UUID id,
		String title,
		String description,
		EventCategory category,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		String venueName,
		String address,
		String neighbourhood,
		double latitude,
		double longitude,
		String imageUrl,
		String imageAlt,
		String imageCredit,
		boolean demo,
		EventStatus status,
		EventType type,
		EventCreatorResponse creator,
		boolean canEdit,
		/** Origine esterna con il link alla pagina ufficiale, o null se l'evento e' stato creato in LINKR. */
		EventSourceResponse source) {
}
